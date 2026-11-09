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
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "reservation:manage");
    StringBuilder sql = new StringBuilder("SELECT order_no orderNo, contact_name contactName, contact_phone contactPhone, check_in checkIn, check_out checkOut, room_count roomCount, total_amount total, status, payment_status paymentStatus, source, created_at createdAt FROM reservation WHERE 1=1");
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    if (status != null && !status.isBlank()) { sql.append(" AND status=?"); args.add(status); }
    if (orderNo != null && !orderNo.isBlank()) { sql.append(" AND order_no=?"); args.add(orderNo); }
    sql.append(" ORDER BY id DESC LIMIT 200");
    List<Map<String, Object>> data = jdbc.queryForList(sql.toString(), args.toArray());
    return Map.of("code", "0", "message", "success", "data", data);
  }

  @GetMapping("/{orderNo}")
  public Map<String, Object> detail(@PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "reservation:manage");
    List<Map<String, Object>> orders = jdbc.queryForList("SELECT id, order_no orderNo, contact_name contactName, contact_phone contactPhone, check_in checkIn, check_out checkOut, guests, room_count roomCount, total_amount total, status, payment_status paymentStatus, source, created_at createdAt FROM reservation WHERE order_no=?", orderNo);
    if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
    Map<String, Object> data = new java.util.LinkedHashMap<>(orders.getFirst());
    data.put("items", jdbc.queryForList("SELECT ri.id, rt.name roomTypeName, ri.quantity, ri.unit_price unitPrice, ri.nights FROM reservation_item ri JOIN room_type rt ON rt.id=ri.room_type_id WHERE ri.reservation_id=? ORDER BY ri.id", data.get("id")));
    data.put("rooms", jdbc.queryForList("SELECT rr.id reservationRoomId, r.id roomId, r.room_no roomNo, r.room_type_id roomTypeId, rr.status, rr.assigned_at assignedAt, rr.check_in_at checkInAt, rr.check_out_at checkOutAt FROM reservation_room rr JOIN room r ON r.id=rr.room_id WHERE rr.reservation_id=? ORDER BY rr.id", data.get("id")));
    data.put("payments", jdbc.queryForList("SELECT payment_no paymentNo, amount, method, status, paid_at paidAt FROM payment WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("charges", jdbc.queryForList("SELECT id, type, amount, description, created_at createdAt FROM folio_charge WHERE reservation_id=? ORDER BY id", data.get("id")));
    data.put("guests", jdbc.queryForList("SELECT id, name, gender, phone, id_type idType, id_no_masked idNoMasked, is_primary isPrimary FROM reservation_guest WHERE reservation_id=? ORDER BY id", data.get("id")));
    return Map.of("code", "0", "message", "success", "data", data);
  }
}
