package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/reservations")
public class AdminReservationController {
  private final SessionService session;
  private final JdbcTemplate jdbc;
  private final AdminReservationService reservations;

  public AdminReservationController(SessionService session, JdbcTemplate jdbc, AdminReservationService reservations) {
    this.session = session;
    this.jdbc = jdbc;
    this.reservations = reservations;
  }

  public record ManualItem(@NotNull Long roomTypeId, @NotNull @Positive Integer quantity) {}
  public record ManualReservationRequest(@NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
      @NotNull @Positive Integer guests, @NotBlank String contactName, @NotBlank String contactPhone,
      Long userId, @NotNull @Size(min = 1) List<@Valid ManualItem> items) {}

  @PostMapping
  public Map<String, Object> create(@Valid @RequestBody ManualReservationRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long operatorId = session.requirePermission(authorization, "reservation:manage").id();
    List<AdminReservationService.Item> items = body.items().stream()
        .map(item -> new AdminReservationService.Item(item.roomTypeId(), item.quantity())).toList();
    return reservations.create(operatorId, body.userId(), body.checkIn(), body.checkOut(), body.guests(),
        body.contactName(), body.contactPhone(), items);
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String contactPhone,
      @RequestParam(required = false) String roomNo,
      @RequestParam(required = false) LocalDate checkInFrom,
      @RequestParam(required = false) LocalDate checkInTo,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "reservation:manage");
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(size, 1), 100);
    StringBuilder where = new StringBuilder(" FROM reservation WHERE 1=1");
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    if (status != null && !status.isBlank()) { where.append(" AND status=?"); args.add(status.trim()); }
    if (orderNo != null && !orderNo.isBlank()) { where.append(" AND order_no LIKE ?"); args.add("%" + orderNo.trim() + "%"); }
    if (contactPhone != null && !contactPhone.isBlank()) { where.append(" AND contact_phone LIKE ?"); args.add("%" + contactPhone.trim() + "%"); }
    if (roomNo != null && !roomNo.isBlank()) {
      where.append(" AND EXISTS (SELECT 1 FROM reservation_room rr JOIN room r ON r.id=rr.room_id WHERE rr.reservation_id=reservation.id AND r.room_no LIKE ?)");
      args.add("%" + roomNo.trim() + "%");
    }
    if (checkInFrom != null) { where.append(" AND check_in>=?"); args.add(checkInFrom); }
    if (checkInTo != null) { where.append(" AND check_in<=?"); args.add(checkInTo); }
    Integer total = jdbc.queryForObject("SELECT COUNT(*)" + where, Integer.class, args.toArray());
    String sql = "SELECT order_no orderNo, contact_name contactName, contact_phone contactPhone, check_in checkIn, check_out checkOut, room_count roomCount, total_amount total, status, payment_status paymentStatus, source, created_at createdAt" + where + " ORDER BY id DESC LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize);
    List<Map<String, Object>> data = jdbc.queryForList(sql, args.toArray());
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("code", "0"); result.put("message", "success"); result.put("data", data);
    result.put("page", safePage); result.put("size", safeSize); result.put("total", total == null ? 0 : total);
    result.put("totalPages", total == null || total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    return result;
  }

  @GetMapping("/{orderNo}")
  public Map<String, Object> detail(@PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "reservation:manage");
    List<Map<String, Object>> orders = jdbc.queryForList("SELECT id, order_no orderNo, contact_name contactName, contact_phone contactPhone, check_in checkIn, check_out checkOut, guests, room_count roomCount, total_amount total, status, payment_status paymentStatus, cancellation_reason cancellationReason, special_request specialRequest, source, created_at createdAt FROM reservation WHERE order_no=?", orderNo);
    if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
    Map<String, Object> data = new java.util.LinkedHashMap<>(orders.getFirst());
    data.put("items", jdbc.queryForList("SELECT ri.id, COALESCE(ri.room_type_name_snapshot, rt.name) roomTypeName, COALESCE(ri.bed_type_snapshot, rt.bed_type) bedType, ri.quantity, ri.unit_price unitPrice, ri.nights, ri.cancellation_policy_snapshot cancellationPolicy FROM reservation_item ri LEFT JOIN room_type rt ON rt.id=ri.room_type_id WHERE ri.reservation_id=? ORDER BY ri.id", data.get("id")));
    data.put("rooms", jdbc.queryForList("SELECT rr.id reservationRoomId, r.id roomId, r.room_no roomNo, r.room_type_id roomTypeId, rr.status, rr.assigned_at assignedAt, rr.check_in_at checkInAt, rr.check_out_at checkOutAt FROM reservation_room rr JOIN room r ON r.id=rr.room_id WHERE rr.reservation_id=? ORDER BY rr.id", data.get("id")));
    data.put("payments", jdbc.queryForList("SELECT payment_no paymentNo, amount, method, status, paid_at paidAt FROM payment WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("charges", jdbc.queryForList("SELECT id, type, amount, description, status, void_reason voidReason, created_at createdAt FROM folio_charge WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("adjustments", jdbc.queryForList("SELECT id, payment_no paymentNo, type, amount, reason, operator_id operatorId, created_at createdAt FROM payment_adjustment WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("events", jdbc.queryForList("SELECT id, event_type eventType, from_status fromStatus, to_status toStatus, reason, operator_id operatorId, created_at createdAt FROM reservation_event WHERE reservation_id=? ORDER BY id DESC", data.get("id")));
    data.put("guests", jdbc.queryForList("SELECT id, name, gender, phone, id_type idType, id_no_masked idNoMasked, is_primary isPrimary FROM reservation_guest WHERE reservation_id=? ORDER BY id", data.get("id")));
    return Map.of("code", "0", "message", "success", "data", data);
  }
}
