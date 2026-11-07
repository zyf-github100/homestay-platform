package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
  private final JdbcTemplate jdbc; private final StringRedisTemplate redis;
  public ReservationController(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }
  public record Item(@NotNull Long roomTypeId, @NotNull Integer quantity) {}
  public record CreateRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut, @NotNull Integer guests, @NotBlank String contactName, @NotBlank String contactPhone, @NotNull List<Item> items) {}

  @PostMapping("/price-check")
  public Map<String,Object> price(@Valid @RequestBody CreateRequest body) { return Map.of("code","0","message","success","data", calculate(body)); }

  @PostMapping
  public Map<String,Object> create(@Valid @RequestBody CreateRequest body, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
    if (!body.checkOut().isAfter(body.checkIn())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "离店日期必须晚于入住日期");
    if (idempotencyKey != null) { String existing = redis.opsForValue().get("homestay:idempotency:reservation:" + idempotencyKey); if (existing != null) return Map.of("code","0","message","订单已创建","data",Map.of("orderNo",existing)); }
    var pricing = calculate(body); String orderNo = "QY" + System.currentTimeMillis(); long nights = ChronoUnit.DAYS.between(body.checkIn(), body.checkOut());
    List<Long> locked = new ArrayList<>();
    try {
      for (Item item : body.items()) {
        List<Long> rooms = jdbc.queryForList("SELECT r.id FROM room r WHERE r.room_type_id=? AND r.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM inventory_lock l WHERE l.room_id=r.id AND l.stay_date>=? AND l.stay_date<?) LIMIT ?", Long.class, item.roomTypeId(), body.checkIn(), body.checkOut(), item.quantity());
        if (rooms.size() < item.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存不足");
        for (Long roomId : rooms) for (LocalDate date = body.checkIn(); date.isBefore(body.checkOut()); date = date.plusDays(1)) {
          String key = "homestay:inventory-lock:" + roomId + ":" + date;
          Boolean acquired = redis.opsForValue().setIfAbsent(key, orderNo, java.time.Duration.ofMinutes(15));
          if (!Boolean.TRUE.equals(acquired)) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存正在被其他订单锁定");
          jdbc.update("INSERT INTO inventory_lock(room_id,stay_date,order_no,expires_at) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 15 MINUTE))", roomId, date, orderNo); locked.add(roomId);
        }
      }
      jdbc.update("INSERT INTO reservation(order_no,contact_name,contact_phone,check_in,check_out,guests,room_count,total_amount) VALUES(?,?,?,?,?,?,?,?)", orderNo, body.contactName(), body.contactPhone(), body.checkIn(), body.checkOut(), body.guests(), body.items().stream().mapToInt(Item::quantity).sum(), pricing.get("total"));
      Long reservationId = jdbc.queryForObject("SELECT id FROM reservation WHERE order_no=?", Long.class, orderNo);
      for (Item item : body.items()) { BigDecimal unit = jdbc.queryForObject("SELECT base_price FROM room_type WHERE id=?", BigDecimal.class, item.roomTypeId()); jdbc.update("INSERT INTO reservation_item(reservation_id,room_type_id,quantity,unit_price,nights) VALUES(?,?,?,?,?)", reservationId, item.roomTypeId(), item.quantity(), unit, nights); }
      if (idempotencyKey != null) redis.opsForValue().set("homestay:idempotency:reservation:" + idempotencyKey, orderNo, java.time.Duration.ofHours(24));
      return Map.of("code","0","message","订单创建成功","data",Map.of("orderNo",orderNo,"total",pricing.get("total"),"status","PENDING_PAYMENT"));
    } catch (RuntimeException ex) { for (Long roomId : locked) { /* DB cleanup is handled by expiry/reconciliation */ } throw ex; }
  }

  @GetMapping
  public Map<String,Object> list() { return Map.of("code","0","message","success","data",jdbc.queryForList("SELECT order_no orderNo, contact_name contactName, check_in checkIn, check_out checkOut, room_count roomCount, total_amount total, status, payment_status paymentStatus, created_at createdAt FROM reservation ORDER BY id DESC")); }

  private Map<String,Object> calculate(CreateRequest body) {
    long nights = Math.max(1, ChronoUnit.DAYS.between(body.checkIn(), body.checkOut())); BigDecimal total = BigDecimal.ZERO;
    for (Item item : body.items()) { BigDecimal price = jdbc.queryForObject("SELECT base_price FROM room_type WHERE id=? AND status='PUBLISHED'", BigDecimal.class, item.roomTypeId()); if (price == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房型不存在"); total = total.add(price.multiply(BigDecimal.valueOf(item.quantity())).multiply(BigDecimal.valueOf(nights))); }
    return Map.of("nights", nights, "total", total);
  }
}
