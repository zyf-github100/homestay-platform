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
      """, (rs, rowNum) -> {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("name", rs.getString("name"));
        row.put("description", rs.getString("description"));
        row.put("bedType", rs.getString("bed_type"));
        row.put("area", rs.getBigDecimal("area_sqm"));
        row.put("maxGuests", rs.getInt("max_guests"));
        row.put("price", rs.getBigDecimal("base_price"));
        row.put("coverImage", rs.getString("cover_image"));
        row.put("roomCount", rs.getInt("room_count"));
        row.put("availableCount", rs.getInt("available_count"));
        long roomTypeId = rs.getLong("id");
        row.put("images", jdbc.queryForList("SELECT id, image_url imageUrl, alt_text altText, sort_order sortOrder FROM room_type_image WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
        row.put("facilities", jdbc.queryForList("SELECT id, name, sort_order sortOrder FROM room_type_facility WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
        return row;
      });
    return Map.of("code", "0", "message", "success", "data", rows, "date", LocalDate.now().toString());
  }

  @GetMapping("/room-types/summary")
  public Map<String, Object> summary() {
    BigDecimal minPrice = jdbc.queryForObject("SELECT COALESCE(MIN(base_price), 0) FROM room_type WHERE status = 'PUBLISHED'", BigDecimal.class);
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM room_type WHERE status = 'PUBLISHED'", Integer.class);
    return Map.of("code", "0", "message", "success", "data", Map.of("roomTypeCount", count, "startingPrice", minPrice));
  }
}
