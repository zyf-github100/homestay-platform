package com.homestay.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatisticsService {
  private final JdbcTemplate jdbc;

  public StatisticsService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> overview(String from, String to) {
    DateRange range = range(from, to);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Integer todayCheckIns = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE check_in=? AND status NOT IN ('CANCELLED','PENDING_PAYMENT')", Integer.class, today);
    Integer todayCheckInRooms = jdbc.queryForObject("SELECT COALESCE(SUM(room_count),0) FROM reservation WHERE check_in=? AND status NOT IN ('CANCELLED','PENDING_PAYMENT')", Integer.class, today);
    Integer todayCheckOuts = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE check_out=? AND status NOT IN ('CANCELLED','PENDING_PAYMENT')", Integer.class, today);
    Integer todayCheckOutRooms = jdbc.queryForObject("SELECT COALESCE(SUM(room_count),0) FROM reservation WHERE check_out=? AND status NOT IN ('CANCELLED','PENDING_PAYMENT')", Integer.class, today);
    Integer stayingOrders = jdbc.queryForObject("SELECT COUNT(DISTINCT reservation_id) FROM reservation_room WHERE status='CHECKED_IN'", Integer.class);
    Integer stayingRooms = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE status='CHECKED_IN'", Integer.class);
    Integer orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE DATE(created_at) BETWEEN ? AND ?", Integer.class, range.from, range.to);
    BigDecimal revenue = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE status='SUCCESS' AND DATE(paid_at) BETWEEN ? AND ?", BigDecimal.class, range.from, range.to);
    BigDecimal refunds = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE status='REFUNDED' AND DATE(updated_at) BETWEEN ? AND ?", BigDecimal.class, range.from, range.to);
    BigDecimal bookedRevenue = jdbc.queryForObject(
        "SELECT COALESCE(SUM(total_amount * DATEDIFF(LEAST(check_out, ?), GREATEST(check_in, ?)) / NULLIF(DATEDIFF(check_out, check_in), 0)),0) "
            + "FROM reservation WHERE status NOT IN ('CANCELLED','PENDING_PAYMENT') AND check_in < ? AND check_out > ?",
        BigDecimal.class, range.to.plusDays(1), range.from, range.to.plusDays(1), range.from);
    BigDecimal outstandingAmount = jdbc.queryForObject(
        "SELECT COALESCE(SUM(GREATEST(r.total_amount + COALESCE((SELECT SUM(fc.amount) FROM folio_charge fc WHERE fc.reservation_id=r.id),0) "
            + "- COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.reservation_id=r.id AND p.status='SUCCESS'),0), 0)),0) "
            + "FROM reservation r WHERE r.status IN ('CONFIRMED','CHECKED_IN','PARTIALLY_CHECKED_IN','PARTIALLY_CHECKED_OUT')",
        BigDecimal.class);
    Integer cancelledOrders = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE status='CANCELLED' AND DATE(updated_at) BETWEEN ? AND ?", Integer.class, range.from, range.to);
    Integer roomCount = jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE status NOT IN ('MAINTENANCE','DISABLED')", Integer.class);
    Integer roomNights = jdbc.queryForObject("SELECT COALESCE(SUM(room_count * DATEDIFF(LEAST(check_out, ?), GREATEST(check_in, ?))),0) FROM reservation WHERE status NOT IN ('CANCELLED','PENDING_PAYMENT') AND check_in < ? AND check_out > ?", Integer.class,
        range.to.plusDays(1), range.from, range.to.plusDays(1), range.from);
    Integer pendingOrders = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE status='PENDING_PAYMENT'", Integer.class);
    long days = range.from.until(range.to.plusDays(1)).getDays();
    BigDecimal capacity = BigDecimal.valueOf(Math.max(0, roomCount == null ? 0 : roomCount) * Math.max(1, days));
    BigDecimal occupancy = capacity.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(roomNights == null ? 0 : roomNights).multiply(BigDecimal.valueOf(100)).divide(capacity, 2, RoundingMode.HALF_UP);
    BigDecimal bookedRoomNights = BigDecimal.valueOf(roomNights == null ? 0 : roomNights);
    BigDecimal adr = bookedRoomNights.signum() == 0 ? BigDecimal.ZERO : bookedRevenue.divide(bookedRoomNights, 2, RoundingMode.HALF_UP);
    BigDecimal revpar = capacity.signum() == 0 ? BigDecimal.ZERO : bookedRevenue.divide(capacity, 2, RoundingMode.HALF_UP);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("from", range.from.toString()); data.put("to", range.to.toString());
    data.put("todayCheckInOrders", value(todayCheckIns)); data.put("todayCheckInRooms", value(todayCheckInRooms));
    data.put("todayCheckOutOrders", value(todayCheckOuts)); data.put("todayCheckOutRooms", value(todayCheckOutRooms));
    data.put("stayingOrders", value(stayingOrders)); data.put("stayingRooms", value(stayingRooms));
    data.put("orderCount", value(orderCount)); data.put("revenue", money(revenue)); data.put("refunds", money(refunds));
    data.put("bookedRevenue", money(bookedRevenue)); data.put("adr", money(adr)); data.put("revpar", money(revpar));
    data.put("outstandingAmount", money(outstandingAmount)); data.put("cancelledOrders", value(cancelledOrders));
    data.put("pendingOrders", value(pendingOrders));
    data.put("availableRooms", jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE status='AVAILABLE'", Integer.class));
    data.put("dirtyRooms", jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE status='DIRTY'", Integer.class));
    data.put("maintenanceRooms", jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE status='MAINTENANCE'", Integer.class));
    data.put("occupancyRate", occupancy);
    return data;
  }

  public List<Map<String, Object>> trends(String from, String to) {
    DateRange range = range(from, to);
    Map<String, Map<String, Object>> daily = new LinkedHashMap<>();
    for (LocalDate date = range.from; !date.isAfter(range.to); date = date.plusDays(1)) {
      Map<String, Object> row = new LinkedHashMap<>(); row.put("date", date.toString()); row.put("orderCount", 0); row.put("revenue", BigDecimal.ZERO); row.put("refunds", BigDecimal.ZERO); row.put("occupiedRooms", 0); row.put("occupancyRate", BigDecimal.ZERO); daily.put(date.toString(), row);
    }
    List<Map<String, Object>> orders = jdbc.queryForList("SELECT DATE(created_at) day, COUNT(*) orderCount FROM reservation WHERE DATE(created_at) BETWEEN ? AND ? GROUP BY DATE(created_at) ORDER BY day", range.from, range.to);
    for (Map<String, Object> row : orders) { Map<String, Object> target = daily.get(String.valueOf(row.get("day"))); if (target != null) target.put("orderCount", row.get("orderCount")); }
    List<Map<String, Object>> payments = jdbc.queryForList("SELECT DATE(paid_at) day, COALESCE(SUM(CASE WHEN status='SUCCESS' THEN amount ELSE 0 END),0) revenue, COALESCE(SUM(CASE WHEN status='REFUNDED' THEN amount ELSE 0 END),0) refunds FROM payment WHERE DATE(paid_at) BETWEEN ? AND ? GROUP BY DATE(paid_at) ORDER BY day", range.from, range.to);
    for (Map<String, Object> row : payments) { Map<String, Object> target = daily.get(String.valueOf(row.get("day"))); if (target != null) { target.put("revenue", money((BigDecimal) row.get("revenue"))); target.put("refunds", money((BigDecimal) row.get("refunds"))); } }
    Integer activeRooms = jdbc.queryForObject("SELECT COUNT(*) FROM room WHERE status NOT IN ('MAINTENANCE','DISABLED')", Integer.class);
    List<Map<String, Object>> occupancy = jdbc.queryForList(
        "SELECT stay_date day, COUNT(DISTINCT room_id) occupiedRooms FROM inventory_lock "
            + "WHERE stay_date BETWEEN ? AND ? AND expires_at IS NULL GROUP BY stay_date ORDER BY day",
        range.from, range.to);
    BigDecimal roomCapacity = BigDecimal.valueOf(Math.max(0, activeRooms == null ? 0 : activeRooms));
    for (Map<String, Object> row : occupancy) {
      Map<String, Object> target = daily.get(String.valueOf(row.get("day")));
      if (target != null) {
        BigDecimal occupiedRooms = BigDecimal.valueOf(((Number) row.get("occupiedRooms")).longValue());
        target.put("occupiedRooms", occupiedRooms.intValue());
        target.put("occupancyRate", roomCapacity.signum() == 0 ? BigDecimal.ZERO
            : occupiedRooms.multiply(BigDecimal.valueOf(100)).divide(roomCapacity, 2, RoundingMode.HALF_UP));
      }
    }
    return new ArrayList<>(daily.values());
  }

  private DateRange range(String from, String to) {
    try {
      LocalDate start = from == null || from.isBlank() ? LocalDate.now(ZoneOffset.UTC).minusDays(6) : LocalDate.parse(from);
      LocalDate end = to == null || to.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(to);
      if (end.isBefore(start) || start.plusDays(366).isBefore(end)) throw new IllegalArgumentException();
      return new DateRange(start, end);
    } catch (DateTimeParseException | IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计日期范围无效，最多支持 367 天");
    }
  }

  private int value(Integer value) { return value == null ? 0 : value; }
  private BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP); }
  private record DateRange(LocalDate from, LocalDate to) {}
}
