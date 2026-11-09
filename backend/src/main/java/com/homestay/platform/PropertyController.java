package com.homestay.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class PropertyController {
  private final JdbcTemplate jdbc;
  public PropertyController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/properties")
  public Map<String, Object> properties() {
    var rows = jdbc.queryForList("SELECT id, name, description, address, phone, status FROM property WHERE status = 'OPEN' ORDER BY id");
    return Map.of("code", "0", "message", "success", "data", rows);
  }

  @GetMapping("/properties/{id}")
  public Map<String, Object> property(@PathVariable Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, name, description, address, phone, status, check_in_time checkInTime, check_out_time checkOutTime, check_in_rule checkInRule, check_out_rule checkOutRule FROM property WHERE id=? AND status='OPEN'",
        id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "民宿不存在");
    return Map.of("code", "0", "message", "success", "data", rows.getFirst());
  }

  @GetMapping("/room-types")
  public Map<String, Object> roomTypes(@RequestParam(required = false) LocalDate checkIn,
      @RequestParam(required = false) LocalDate checkOut,
      @RequestParam(required = false, defaultValue = "1") Integer guests) {
    LocalDate start = checkIn == null ? LocalDate.now(ZoneOffset.UTC).plusDays(1) : checkIn;
    LocalDate end = checkOut == null ? start.plusDays(1) : checkOut;
    validateSearchDates(start, end, guests);
    List<Map<String, Object>> sourceRows = jdbc.queryForList("""
      SELECT rt.id, rt.name, rt.description, rt.bed_type, rt.area_sqm, rt.max_guests,
             rt.base_price, rt.cover_image, COUNT(r.id) AS room_count,
             SUM(CASE WHEN r.status = 'AVAILABLE'
               AND NOT EXISTS (
                 SELECT 1 FROM inventory_lock l
                 WHERE l.room_id = r.id
                   AND l.stay_date >= ? AND l.stay_date < ?
                   AND (l.expires_at IS NULL OR l.expires_at > NOW())
               ) THEN 1 ELSE 0 END) AS available_count
      FROM room_type rt LEFT JOIN room r ON r.room_type_id = rt.id
      JOIN property p ON p.id = rt.property_id
      WHERE rt.status = 'PUBLISHED' AND p.status = 'OPEN' AND rt.max_guests >= ?
      GROUP BY rt.id ORDER BY rt.id
      """, start, end, guests);
    List<Map<String, Object>> rows = new ArrayList<>();
    sourceRows.forEach(source -> {
        Map<String, Object> data = new LinkedHashMap<>(source);
        data.put("bedType", data.remove("bed_type"));
        data.put("area", data.remove("area_sqm"));
        data.put("maxGuests", data.remove("max_guests"));
        data.put("price", data.remove("base_price"));
        data.put("coverImage", data.remove("cover_image"));
        data.put("roomCount", data.remove("room_count"));
        data.put("availableCount", data.remove("available_count"));
        long roomTypeId = ((Number) data.get("id")).longValue();
        data.put("images", jdbc.queryForList("SELECT id, image_url imageUrl, alt_text altText, sort_order sortOrder FROM room_type_image WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
        data.put("facilities", jdbc.queryForList("SELECT id, name, sort_order sortOrder FROM room_type_facility WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
        rows.add(data);
    });
    return Map.of("code", "0", "message", "success", "data", rows, "checkIn", start.toString(), "checkOut", end.toString());
  }

  @GetMapping("/room-types/summary")
  public Map<String, Object> summary() {
    BigDecimal minPrice = jdbc.queryForObject("SELECT COALESCE(MIN(base_price), 0) FROM room_type WHERE status = 'PUBLISHED'", BigDecimal.class);
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM room_type WHERE status = 'PUBLISHED'", Integer.class);
    return Map.of("code", "0", "message", "success", "data", Map.of("roomTypeCount", count, "startingPrice", minPrice));
  }

  @GetMapping("/room-types/{id}")
  public Map<String, Object> roomType(@PathVariable Long id,
      @RequestParam(required = false) LocalDate checkIn,
      @RequestParam(required = false) LocalDate checkOut,
      @RequestParam(required = false, defaultValue = "1") Integer guests) {
    Map<String, Object> all = roomTypes(checkIn, checkOut, guests);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) all.get("data");
    return rows.stream().filter(row -> ((Number) row.get("id")).longValue() == id)
        .findFirst()
        .map(row -> Map.of("code", "0", "message", "success", "data", row))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "房型不存在"));
  }

  @GetMapping("/room-types/{id}/availability")
  public Map<String, Object> availability(@PathVariable Long id,
      @RequestParam LocalDate checkIn, @RequestParam LocalDate checkOut,
      @RequestParam(required = false, defaultValue = "1") Integer guests) {
    Map<String, Object> roomType = roomType(id, checkIn, checkOut, guests);
    Map<String, Object> data = (Map<String, Object>) roomType.get("data");
    return Map.of("code", "0", "message", "success", "data", Map.of(
        "roomTypeId", id, "checkIn", checkIn.toString(), "checkOut", checkOut.toString(),
        "guests", guests, "availableCount", data.get("availableCount")));
  }

  private void validateSearchDates(LocalDate checkIn, LocalDate checkOut, Integer guests) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (guests == null || guests < 1) throw badRequest("入住人数必须大于 0");
    if (checkIn.isBefore(today)) throw badRequest("入住日期不能早于当前日期");
    if (!checkOut.isAfter(checkIn)) throw badRequest("离店日期必须晚于入住日期");
    if (checkOut.isAfter(checkIn.plusDays(366))) throw badRequest("预订日期范围不能超过 366 天");
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
