package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
  private final JdbcTemplate jdbc; private final StringRedisTemplate redis; private final SessionService session; private final PaymentService payments;
  private final AuditService audit;
  public ReservationController(JdbcTemplate jdbc, StringRedisTemplate redis, SessionService session, PaymentService payments) { this(jdbc, redis, session, payments, null); }
  @Autowired
  public ReservationController(JdbcTemplate jdbc, StringRedisTemplate redis, SessionService session, PaymentService payments, AuditService audit) { this.jdbc = jdbc; this.redis = redis; this.session = session; this.payments = payments; this.audit = audit; }
  public record Item(@NotNull Long roomTypeId, @NotNull @Positive Integer quantity) {}
  public record CreateRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
      @NotNull @Positive Integer guests, @NotBlank String contactName,
      @NotBlank String contactPhone, @NotNull @Size(min = 1) List<@Valid Item> items,
      List<@Positive Long> guestIds) {}

  @PostMapping("/price-check")
  public Map<String,Object> price(@Valid @RequestBody CreateRequest body) { return Map.of("code","0","message","success","data", calculate(body)); }

  @PostMapping
  @Transactional
  public Map<String,Object> create(@Valid @RequestBody CreateRequest body, @RequestHeader(value="Authorization", required=false) String authorization, @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey) {
    SessionService.SessionUser user = session.requirePermission(authorization, "reservation:create");
    validateDates(body);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      String existing = redis.opsForValue().get("homestay:idempotency:reservation:" + idempotencyKey);
      if (existing != null) {
        Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE order_no=? AND user_id=?", Integer.class, existing, user.id());
        if (owned != null && owned > 0) return Map.of("code","0","message","订单已创建","data",Map.of("orderNo",existing));
        redis.delete("homestay:idempotency:reservation:" + idempotencyKey);
      }
    }
    var pricing = calculate(body); String orderNo = "QY" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); long nights = ChronoUnit.DAYS.between(body.checkIn(), body.checkOut());
    List<String> lockedKeys = new ArrayList<>();
    try {
      jdbc.update("DELETE FROM inventory_lock WHERE expires_at <= NOW()");
      for (Item item : body.items()) {
        List<Long> rooms = jdbc.queryForList("SELECT r.id FROM room r WHERE r.room_type_id=? AND r.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM inventory_lock l WHERE l.room_id=r.id AND l.stay_date>=? AND l.stay_date<?) AND NOT EXISTS (SELECT 1 FROM inventory_override o WHERE o.room_id=r.id AND o.stay_date>=? AND o.stay_date<? AND o.status='BLOCKED') LIMIT ?", Long.class, item.roomTypeId(), body.checkIn(), body.checkOut(), body.checkIn(), body.checkOut(), item.quantity());
        if (rooms.size() < item.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存不足");
        for (Long roomId : rooms) for (LocalDate date = body.checkIn(); date.isBefore(body.checkOut()); date = date.plusDays(1)) {
          String key = "homestay:inventory-lock:" + roomId + ":" + date;
          Boolean acquired = redis.opsForValue().setIfAbsent(key, orderNo, java.time.Duration.ofMinutes(15));
          if (!Boolean.TRUE.equals(acquired)) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存正在被其他订单锁定");
          lockedKeys.add(key);
          jdbc.update("INSERT INTO inventory_lock(room_id,stay_date,order_no,expires_at) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 15 MINUTE))", roomId, date, orderNo);
        }
      }
      jdbc.update("INSERT INTO reservation(order_no,user_id,contact_name,contact_phone,check_in,check_out,guests,room_count,total_amount,cancellation_policy) VALUES(?,?,?,?,?,?,?,?,?,'STANDARD_24H')", orderNo, user.id(), body.contactName(), body.contactPhone(), body.checkIn(), body.checkOut(), body.guests(), body.items().stream().mapToInt(Item::quantity).sum(), pricing.get("total"));
      Long reservationId = jdbc.queryForObject("SELECT id FROM reservation WHERE order_no=?", Long.class, orderNo);
      for (Item item : body.items()) {
        BigDecimal unit = jdbc.queryForObject(
            "SELECT rt.base_price FROM room_type rt JOIN property p ON p.id=rt.property_id "
                + "WHERE rt.id=? AND rt.status='PUBLISHED' AND p.status='OPEN'",
            BigDecimal.class, item.roomTypeId());
        Map<String, Object> snapshot = jdbc.queryForMap(
            "SELECT name, bed_type bedType FROM room_type WHERE id=?", item.roomTypeId());
        jdbc.update("INSERT INTO reservation_item(reservation_id,room_type_id,quantity,unit_price,nights,room_type_name_snapshot,bed_type_snapshot,cancellation_policy_snapshot) VALUES(?,?,?,?,?,?,?,?)",
            reservationId, item.roomTypeId(), item.quantity(), unit, nights, snapshot.get("name"), snapshot.get("bedType"), "STANDARD_24H");
      }
      saveGuestSnapshots(user.id(), reservationId, body.guestIds(), body.guests());
      if (idempotencyKey != null) redis.opsForValue().set("homestay:idempotency:reservation:" + idempotencyKey, orderNo, java.time.Duration.ofHours(24));
      if (audit != null) audit.record(user.id(), "RESERVATION_CREATE", "RESERVATION", orderNo, "SUCCESS", "创建订单");
      return Map.of("code","0","message","订单创建成功","data",Map.of("orderNo",orderNo,"total",pricing.get("total"),"status","PENDING_PAYMENT"));
    } catch (RuntimeException ex) {
      for (String key : lockedKeys) {
        if (orderNo.equals(redis.opsForValue().get(key))) redis.delete(key);
      }
      throw ex;
    }
  }

  @GetMapping
  public Map<String,Object> list(@RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestHeader(value="Authorization", required=false) String authorization) {
    SessionService.SessionUser user = session.requirePermission(authorization, "reservation:view:self");
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(size, 1), 50);
    StringBuilder where = new StringBuilder(" FROM reservation WHERE user_id=?");
    List<Object> args = new ArrayList<>(); args.add(user.id());
    if (status != null && !status.isBlank()) { where.append(" AND status=?"); args.add(status.trim()); }
    Integer total = jdbc.queryForObject("SELECT COUNT(*)" + where, Integer.class, args.toArray());
    List<Map<String, Object>> data = jdbc.queryForList("SELECT order_no orderNo, contact_name contactName, check_in checkIn, check_out checkOut, room_count roomCount, total_amount total, status, payment_status paymentStatus, created_at createdAt" + where + " ORDER BY id DESC LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize), args.toArray());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("code", "0"); result.put("message", "success"); result.put("data", data);
    result.put("page", safePage); result.put("size", safeSize); result.put("total", total == null ? 0 : total);
    result.put("totalPages", total == null || total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    return result;
  }

  /** Backward-compatible overload used by integrations that only pass the auth header. */
  public Map<String, Object> list(String authorization) {
    return list(null, 1, 20, authorization);
  }

  @GetMapping("/{orderNo}")
  public Map<String, Object> detail(@PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    SessionService.SessionUser user = session.requirePermission(authorization, "reservation:view:self");
    List<Map<String, Object>> orders = jdbc.queryForList("SELECT id, order_no orderNo, contact_name contactName, contact_phone contactPhone, check_in checkIn, check_out checkOut, guests, room_count roomCount, total_amount total, cancellation_policy cancellationPolicy, status, payment_status paymentStatus, created_at createdAt FROM reservation WHERE order_no=? AND user_id=?", orderNo, user.id());
    if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
    Map<String, Object> data = new LinkedHashMap<>(orders.getFirst());
    data.put("items", jdbc.queryForList("SELECT ri.id, COALESCE(ri.room_type_name_snapshot, rt.name) roomTypeName, COALESCE(ri.bed_type_snapshot, rt.bed_type) bedType, ri.quantity, ri.unit_price unitPrice, ri.nights, ri.cancellation_policy_snapshot cancellationPolicy FROM reservation_item ri LEFT JOIN room_type rt ON rt.id=ri.room_type_id WHERE ri.reservation_id=? ORDER BY ri.id", data.get("id")));
    data.put("rooms", jdbc.queryForList("SELECT rr.id reservationRoomId, r.room_no roomNo, r.room_type_id roomTypeId, rr.status, rr.assigned_at assignedAt, rr.check_in_at checkInAt, rr.check_out_at checkOutAt FROM reservation_room rr JOIN room r ON r.id=rr.room_id WHERE rr.reservation_id=? ORDER BY rr.id", data.get("id")));
    data.put("payments", jdbc.queryForList("SELECT payment_no paymentNo, amount, method, status, paid_at paidAt FROM payment WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("charges", jdbc.queryForList("SELECT id, type, amount, description, created_at createdAt FROM folio_charge WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("guests", jdbc.queryForList("SELECT id, name, gender, phone, id_type idType, id_no_masked idNoMasked, is_primary isPrimary FROM reservation_guest WHERE reservation_id=? ORDER BY id", data.get("id")));
    return Map.of("code", "0", "message", "success", "data", data);
  }

  @PostMapping("/{orderNo}/cancel")
  public Map<String, Object> cancel(
      @PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.cancelReservation(orderNo, session.requirePermission(authorization, "reservation:cancel:self").id());
  }

  @GetMapping("/{orderNo}/cancel-preview")
  public Map<String, Object> cancelPreview(@PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.cancellationPreview(orderNo,
        session.requirePermission(authorization, "reservation:cancel:self").id());
  }

  private Map<String,Object> calculate(CreateRequest body) {
    validateDates(body);
    long nights = ChronoUnit.DAYS.between(body.checkIn(), body.checkOut()); BigDecimal total = BigDecimal.ZERO;
    int capacity = 0;
    for (Item item : body.items()) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT rt.base_price price, rt.max_guests maxGuests FROM room_type rt "
              + "JOIN property p ON p.id=rt.property_id "
              + "WHERE rt.id=? AND rt.status='PUBLISHED' AND p.status='OPEN'",
          item.roomTypeId());
      if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房型不存在或民宿暂停营业");
      Map<String, Object> roomType = rows.getFirst();
      BigDecimal price = (BigDecimal) roomType.get("price");
      int maxGuests = ((Number) roomType.get("maxGuests")).intValue();
      capacity += maxGuests * item.quantity();
      total = total.add(price.multiply(BigDecimal.valueOf(item.quantity())).multiply(BigDecimal.valueOf(nights)));
    }
    if (body.guests() > capacity) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入住人数超过所选房型容量");
    return Map.of("nights", nights, "total", total);
  }

  private void validateDates(CreateRequest body) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (body.checkIn().isBefore(today)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入住日期不能早于当前日期");
    if (!body.checkOut().isAfter(body.checkIn())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "离店日期必须晚于入住日期");
    if (body.checkOut().isAfter(body.checkIn().plusDays(366))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预订日期范围不能超过 366 天");
    }
  }

  private void saveGuestSnapshots(Long userId, Long reservationId, List<Long> guestIds, int guestCount) {
    if (guestIds == null || guestIds.isEmpty()) return;
    if (guestIds.size() > guestCount) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入住人数量不能超过入住人数");
    }
    if (new HashSet<>(guestIds).size() != guestIds.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入住人不能重复选择");
    }
    boolean primary = true;
    for (Long guestId : guestIds) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT name, gender, phone, id_type idType, id_no_ciphertext ciphertext, id_no_hash idNoHash, id_no_masked idNoMasked "
              + "FROM guest_profile WHERE id=? AND user_id=? AND deleted_at IS NULL",
          guestId, userId);
      if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入住人资料不存在");
      Map<String, Object> guest = rows.getFirst();
      jdbc.update(
          "INSERT INTO reservation_guest(reservation_room_id,reservation_id,guest_profile_id,name,gender,phone,id_type,id_no_ciphertext,id_no_hash,id_no_masked,is_primary) "
              + "VALUES(NULL,?,?,?,?,?,?,?,?,?,?)",
          reservationId, guestId, guest.get("name"), guest.get("gender"), guest.get("phone"),
          guest.get("idType"), guest.get("ciphertext"), guest.get("idNoHash"), guest.get("idNoMasked"), primary);
      primary = false;
    }
  }
}
