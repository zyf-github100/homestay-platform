package com.homestay.platform;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryController {
  private static final Set<String> BLOCKING_RESERVATION_STATUSES = Set.of(
      "PENDING_PAYMENT", "CONFIRMED", "CHECKED_IN", "PARTIALLY_CHECKED_IN", "PARTIALLY_CHECKED_OUT");

  private final SessionService session;
  private final JdbcTemplate jdbc;

  public InventoryController(SessionService session, JdbcTemplate jdbc) {
    this.session = session;
    this.jdbc = jdbc;
  }

  @GetMapping("/calendar")
  public Map<String, Object> calendar(@RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "inventory:view");
    LocalDate start = parse(from, LocalDate.now(ZoneOffset.UTC));
    LocalDate end = parse(to, start.plusDays(13));
    if (end.isBefore(start)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");
    if (end.isAfter(start.plusDays(61))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存日历最多查询 62 天");

    List<Map<String, Object>> rooms = jdbc.queryForList(
        "SELECT r.id roomId, r.room_no roomNo, r.room_type_id roomTypeId, rt.name roomTypeName, r.status sourceStatus "
            + "FROM room r JOIN room_type rt ON rt.id=r.room_type_id ORDER BY rt.id, r.room_no");
    Map<String, Map<String, Object>> locks = new HashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(
        "SELECT room_id roomId, stay_date stayDate, order_no orderNo FROM inventory_lock "
            + "WHERE stay_date>=? AND stay_date<=? AND (expires_at IS NULL OR expires_at>NOW())",
        start, end)) {
      locks.put(key(longValue(row.get("roomId")), date(row.get("stayDate"))),
          row);
    }

    Map<Long, List<Map<String, Object>>> occupied = new HashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(
        "SELECT rr.room_id roomId, r.order_no orderNo, r.check_in checkIn, r.check_out checkOut "
            + "FROM reservation_room rr JOIN reservation r ON r.id=rr.reservation_id "
            + "WHERE rr.status='CHECKED_IN' AND r.status IN ('CHECKED_IN','PARTIALLY_CHECKED_IN','PARTIALLY_CHECKED_OUT') "
            + "AND r.check_in<=? AND r.check_out>?",
        end, start)) {
      occupied.computeIfAbsent(longValue(row.get("roomId")), ignored -> new ArrayList<>()).add(row);
    }

    List<Map<String, Object>> days = new ArrayList<>();
    Map<Long, Map<String, List<Map<String, Object>>>> typeDays = new LinkedHashMap<>();
    for (LocalDate current = start; !current.isAfter(end); current = current.plusDays(1)) {
      LocalDate calendarDate = current;
      Map<String, Integer> summary = emptySummary();
      List<Map<String, Object>> dayRooms = new ArrayList<>();
      for (Map<String, Object> room : rooms) {
        Long roomId = longValue(room.get("roomId"));
        String category = category(room, current, locks.get(key(roomId, current)), occupied.get(roomId));
        summary.put(categoryKey(category), summary.get(categoryKey(category)) + 1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("roomId", roomId);
        item.put("roomNo", room.get("roomNo"));
        item.put("roomTypeId", room.get("roomTypeId"));
        item.put("roomTypeName", room.get("roomTypeName"));
        item.put("category", category);
        item.put("sourceStatus", room.get("sourceStatus"));
        Map<String, Object> lock = locks.get(key(roomId, current));
        if (lock != null) item.put("orderNo", lock.get("orderNo"));
        List<Map<String, Object>> stays = occupied.get(roomId);
        if (stays != null) {
          stays.stream().filter(stay -> inStay(calendarDate, stay)).findFirst().ifPresent(stay -> item.put("orderNo", stay.get("orderNo")));
        }
        dayRooms.add(item);

        Long typeId = longValue(room.get("roomTypeId"));
        Map<String, List<Map<String, Object>>> dateSummary = typeDays.computeIfAbsent(typeId, ignored -> new LinkedHashMap<>());
        List<Map<String, Object>> typeRows = dateSummary.computeIfAbsent(current.toString(), ignored -> new ArrayList<>());
        typeRows.add(item);
      }
      summary.put("total", rooms.size());
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("date", current.toString());
      day.put("summary", summary);
      day.put("rooms", dayRooms);
      days.add(day);
    }

    List<Map<String, Object>> roomTypes = new ArrayList<>();
    for (Map<String, Object> type : jdbc.queryForList("SELECT id roomTypeId, name roomTypeName FROM room_type ORDER BY id")) {
      Long typeId = longValue(type.get("roomTypeId"));
      Map<String, Object> typeData = new LinkedHashMap<>(type);
      List<Map<String, Object>> typeDaysData = new ArrayList<>();
      Map<String, List<Map<String, Object>>> dates = typeDays.getOrDefault(typeId, Map.of());
      for (LocalDate current = start; !current.isAfter(end); current = current.plusDays(1)) {
        List<Map<String, Object>> rows = dates.getOrDefault(current.toString(), List.of());
        Map<String, Integer> summary = emptySummary();
        rows.forEach(row -> summary.put(categoryKey(String.valueOf(row.get("category"))),
            summary.get(categoryKey(String.valueOf(row.get("category")))) + 1));
        summary.put("total", rows.size());
        typeDaysData.add(Map.of("date", current.toString(), "summary", summary));
      }
      typeData.put("days", typeDaysData);
      roomTypes.add(typeData);
    }

    return Map.of("code", "0", "message", "success", "data",
        Map.of("from", start.toString(), "to", end.toString(), "days", days, "roomTypes", roomTypes));
  }

  private String category(Map<String, Object> room, LocalDate date, Map<String, Object> lock,
      List<Map<String, Object>> stays) {
    String status = String.valueOf(room.get("sourceStatus"));
    if ("MAINTENANCE".equals(status)) return "MAINTENANCE";
    if ("DISABLED".equals(status)) return "DISABLED";
    if ("DIRTY".equals(status)) return "DIRTY";
    if ("OCCUPIED".equals(status)) return "OCCUPIED";
    if (stays != null && stays.stream().anyMatch(stay -> inStay(date, stay))) return "OCCUPIED";
    if (lock != null || "RESERVED".equals(status)) return "RESERVED";
    return "AVAILABLE";
  }

  private boolean inStay(LocalDate date, Map<String, Object> stay) {
    return !date.isBefore(date(stay.get("checkIn"))) && date.isBefore(date(stay.get("checkOut")));
  }

  private Map<String, Integer> emptySummary() {
    Map<String, Integer> summary = new LinkedHashMap<>();
    summary.put("available", 0);
    summary.put("reserved", 0);
    summary.put("occupied", 0);
    summary.put("maintenance", 0);
    summary.put("disabled", 0);
    summary.put("dirty", 0);
    return summary;
  }

  private String categoryKey(String category) {
    return switch (category) {
      case "RESERVED" -> "reserved";
      case "OCCUPIED" -> "occupied";
      case "MAINTENANCE" -> "maintenance";
      case "DISABLED" -> "disabled";
      case "DIRTY" -> "dirty";
      default -> "available";
    };
  }

  private String key(Long roomId, LocalDate date) {
    return roomId + ":" + date;
  }

  private LocalDate parse(String value, LocalDate fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式应为 YYYY-MM-DD");
    }
  }

  private LocalDate date(Object value) {
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    if (value instanceof LocalDate localDate) return localDate;
    return LocalDate.parse(String.valueOf(value));
  }

  private Long longValue(Object value) {
    return ((Number) value).longValue();
  }
}
