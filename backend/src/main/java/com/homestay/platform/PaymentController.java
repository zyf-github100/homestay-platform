package com.homestay.platform;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final JdbcTemplate jdbc; private final StringRedisTemplate redis;
  public PaymentController(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }
  @PostMapping("/reservations/{orderNo}/pay")
  public Map<String,Object> pay(@PathVariable String orderNo, @RequestParam(defaultValue="success") String result) {
    var rows=jdbc.queryForList("SELECT id,total_amount,status FROM reservation WHERE order_no=?",orderNo); if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"订单不存在");
    String status=(String)rows.getFirst().get("status"); if(!"PENDING_PAYMENT".equals(status)) return Map.of("code","0","message","订单已处理","data",Map.of("orderNo",orderNo,"status",status));
    if ("success".equalsIgnoreCase(result)) { jdbc.update("UPDATE reservation SET status='CONFIRMED',payment_status='PAID' WHERE order_no=?",orderNo); redis.delete("homestay:idempotency:payment:"+orderNo); return Map.of("code","0","message","模拟支付成功","data",Map.of("orderNo",orderNo,"status","CONFIRMED")); }
    if ("cancel".equalsIgnoreCase(result)) { jdbc.update("UPDATE reservation SET status='CANCELLED',payment_status='CANCELLED' WHERE order_no=?",orderNo); return Map.of("code","0","message","支付已取消","data",Map.of("orderNo",orderNo,"status","CANCELLED")); }
    return Map.of("code","0","message","模拟支付失败","data",Map.of("orderNo",orderNo,"status", "PENDING_PAYMENT"));
  }
  @PostMapping("/reservations/{orderNo}/cancel")
  public Map<String,Object> cancel(@PathVariable String orderNo) { int n=jdbc.update("UPDATE reservation SET status='CANCELLED' WHERE order_no=? AND status IN ('PENDING_PAYMENT','CONFIRMED')",orderNo); if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单当前不可取消"); return Map.of("code","0","message","订单已取消","data",Map.of("orderNo",orderNo,"status","CANCELLED")); }
}
