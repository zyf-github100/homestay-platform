package com.homestay.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

  @GetMapping("/room-types")
  public Map<String, Object> roomTypes() {
    List<Map<String, Object>> rows = jdbc.query("""
      SELECT rt.id, rt.name, rt.description, rt.bed_type, rt.area_sqm, rt.max_guests,
             rt.base_price, rt.cover_image, COUNT(r.id) AS room_count,
             SUM(CASE WHEN r.status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_count
      FROM room_type rt LEFT JOIN room r ON r.room_type_id = rt.id
      JOIN property p ON p.id = rt.property_id
      WHERE rt.status = 'PUBLISHED' AND p.status = 'OPEN'
      GROUP BY rt.id ORDER BY rt.id
      """, (rs, rowNum) -> Map.of(
        "id", rs.getLong("id"), "name", rs.getString("name"),
        "description", rs.getString("description"), "bedType", rs.getString("bed_type"),
        "area", rs.getBigDecimal("area_sqm"), "maxGuests", rs.getInt("max_guests"),
        "price", rs.getBigDecimal("base_price"), "coverImage", rs.getString("cover_image"),
        "roomCount", rs.getInt("room_count"), "availableCount", rs.getInt("available_count")));
    return Map.of("code", "0", "message", "success", "data", rows, "date", LocalDate.now().toString());
  }

  @GetMapping("/room-types/summary")
  public Map<String, Object> summary() {
    BigDecimal minPrice = jdbc.queryForObject("SELECT COALESCE(MIN(base_price), 0) FROM room_type WHERE status = 'PUBLISHED'", BigDecimal.class);
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM room_type WHERE status = 'PUBLISHED'", Integer.class);
    return Map.of("code", "0", "message", "success", "data", Map.of("roomTypeCount", count, "startingPrice", minPrice));
  }
}
