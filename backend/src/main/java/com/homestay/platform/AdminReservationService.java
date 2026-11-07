package com.homestay.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates a front-desk order while keeping the same inventory lock rules as online orders. */
@Service
public class AdminReservationService {
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;
  private final AuditService audit;

  public AdminReservationService(JdbcTemplate jdbc, StringRedisTemplate redis, AuditService audit) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.audit = audit;
  }

  public record Item(Long roomTypeId, Integer quantity) {}

  @Transactional
  public Map<String, Object> create(Long operatorId, Long userId, LocalDate checkIn, LocalDate checkOut,
      Integer guests, String contactName, String contactPhone, List<Item> items) {
    validate(checkIn, checkOut, guests, contactName, contactPhone, items);
    if (userId != null) {
      Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status='ACTIVE'", Integer.class, userId);
      if (users == null || users == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关联用户不存在或已停用");
    }

    long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
    BigDecimal total = BigDecimal.ZERO;
    for (Item item : items) {
      BigDecimal price = jdbc.queryForObject(
          "SELECT base_price FROM room_type rt JOIN property p ON p.id=rt.property_id WHERE rt.id=? AND rt.status='PUBLISHED' AND p.status='OPEN'",
          BigDecimal.class, item.roomTypeId());
      if (price == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房型不存在或已下架");
      total = total.add(price.multiply(BigDecimal.valueOf(item.quantity())).multiply(BigDecimal.valueOf(nights)));
    }

    String orderNo = "QY" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    List<String> lockedKeys = new ArrayList<>();
    try {
      jdbc.update("DELETE FROM inventory_lock WHERE expires_at IS NOT NULL AND expires_at <= NOW()");
      for (Item item : items) {
        List<Long> rooms = jdbc.queryForList(
            "SELECT r.id FROM room r WHERE r.room_type_id=? AND r.status='AVAILABLE' "
                + "AND NOT EXISTS (SELECT 1 FROM inventory_lock l WHERE l.room_id=r.id AND l.stay_date>=? AND l.stay_date<?) "
                + "LIMIT ?",
            Long.class, item.roomTypeId(), checkIn, checkOut, item.quantity());
        if (rooms.size() < item.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存不足");
        for (Long roomId : rooms) {
          for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            String key = "homestay:inventory-lock:" + roomId + ":" + date;
            Boolean acquired = redis.opsForValue().setIfAbsent(key, orderNo, java.time.Duration.ofMinutes(15));
            if (!Boolean.TRUE.equals(acquired)) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存正在被其他订单锁定");
            lockedKeys.add(key);
            jdbc.update("INSERT INTO inventory_lock(room_id,stay_date,order_no,expires_at) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 15 MINUTE))",
                roomId, date, orderNo);
          }
        }
      }

      jdbc.update("INSERT INTO reservation(order_no,user_id,contact_name,contact_phone,check_in,check_out,guests,room_count,total_amount,source,created_by) VALUES(?,?,?,?,?,?,?,?,?,'MANUAL',?)",
          orderNo, userId, contactName.trim(), contactPhone.trim(), checkIn, checkOut, guests,
          items.stream().mapToInt(Item::quantity).sum(), total, operatorId);
      Long reservationId = jdbc.queryForObject("SELECT id FROM reservation WHERE order_no=?", Long.class, orderNo);
      for (Item item : items) {
        BigDecimal price = jdbc.queryForObject("SELECT base_price FROM room_type WHERE id=?", BigDecimal.class, item.roomTypeId());
        jdbc.update("INSERT INTO reservation_item(reservation_id,room_type_id,quantity,unit_price,nights) VALUES(?,?,?,?,?)",
            reservationId, item.roomTypeId(), item.quantity(), price, nights);
      }
      audit.record(operatorId, "RESERVATION_CREATE_MANUAL", "RESERVATION", orderNo, "SUCCESS", "前台创建人工订单");
      return Map.of("code", "0", "message", "人工订单创建成功", "data",
          Map.of("orderNo", orderNo, "total", total, "status", "PENDING_PAYMENT", "source", "MANUAL"));
    } catch (RuntimeException ex) {
      for (String key : lockedKeys) {
        if (orderNo.equals(redis.opsForValue().get(key))) redis.delete(key);
      }
      throw ex;
    }
  }

  private void validate(LocalDate checkIn, LocalDate checkOut, Integer guests, String contactName,
      String contactPhone, List<Item> items) {
    if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "离店日期必须晚于入住日期");
    }
    if (guests == null || guests < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入住人数必须大于 0");
    if (contactName == null || contactName.isBlank() || contactPhone == null || contactPhone.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系人和联系电话不能为空");
    }
    if (items == null || items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个房型");
    for (Item item : items) {
      if (item == null || item.roomTypeId() == null || item.quantity() == null || item.quantity() < 1) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "房型数量必须大于 0");
      }
    }
  }
}
