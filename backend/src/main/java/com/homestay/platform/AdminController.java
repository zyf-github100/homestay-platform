package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final JdbcTemplate jdbc; private final SessionService session;
  public AdminController(JdbcTemplate jdbc, SessionService session) { this.jdbc = jdbc; this.session = session; }
  public record RoomTypeRequest(@NotBlank String name, String description, @NotBlank String bedType, BigDecimal area, Integer maxGuests, BigDecimal price, String coverImage) {}
  public record RoomRequest(Long roomTypeId, @NotBlank String roomNo, String floorNo, String status, String remark) {}

  @GetMapping("/room-types")
  public Map<String,Object> roomTypes(@RequestHeader(value="Authorization", required=false) String authorization) { guard(authorization, "room-type:view"); return Map.of("code","0","message","success","data", jdbc.queryForList("SELECT id, name, description, bed_type bedType, area_sqm area, max_guests maxGuests, base_price price, cover_image coverImage, status FROM room_type ORDER BY id DESC")); }

  @PostMapping("/room-types")
  public Map<String,Object> createRoomType(@RequestHeader(value="Authorization", required=false) String authorization, @Valid @RequestBody RoomTypeRequest body) { guard(authorization, "room-type:manage");
    Long propertyId = jdbc.queryForObject("SELECT id FROM property ORDER BY id LIMIT 1", Long.class);
    jdbc.update("INSERT INTO room_type(property_id,name,description,bed_type,area_sqm,max_guests,base_price,cover_image) VALUES(?,?,?,?,?,?,?,?)", propertyId, body.name(), body.description(), body.bedType(), body.area(), body.maxGuests(), body.price(), body.coverImage());
    return Map.of("code","0","message","房型创建成功");
  }

  @PutMapping("/room-types/{id}")
  public Map<String,Object> updateRoomType(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable Long id, @Valid @RequestBody RoomTypeRequest body) { guard(authorization, "room-type:manage");
    jdbc.update("UPDATE room_type SET name=?,description=?,bed_type=?,area_sqm=?,max_guests=?,base_price=?,cover_image=? WHERE id=?", body.name(), body.description(), body.bedType(), body.area(), body.maxGuests(), body.price(), body.coverImage(), id);
    return Map.of("code","0","message","房型更新成功");
  }

  @PatchMapping("/room-types/{id}/status")
  public Map<String,Object> roomTypeStatus(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable Long id, @RequestParam String status) { guard(authorization, "room-type:manage"); jdbc.update("UPDATE room_type SET status=? WHERE id=?", status, id); return Map.of("code","0","message","状态已更新"); }

  @GetMapping("/rooms")
  public Map<String,Object> rooms(@RequestHeader(value="Authorization", required=false) String authorization) { guard(authorization, "room:view"); return Map.of("code","0","message","success","data", jdbc.queryForList("SELECT r.id, r.room_no roomNo, r.floor_no floorNo, r.status, r.remark, rt.name roomTypeName FROM room r JOIN room_type rt ON rt.id=r.room_type_id ORDER BY r.id DESC")); }

  @PostMapping("/rooms")
  public Map<String,Object> createRoom(@RequestHeader(value="Authorization", required=false) String authorization, @Valid @RequestBody RoomRequest body) { guard(authorization, "room:manage");
    Long propertyId = jdbc.queryForObject("SELECT property_id FROM room_type WHERE id=?", Long.class, body.roomTypeId());
    jdbc.update("INSERT INTO room(property_id,room_type_id,room_no,floor_no,status,remark) VALUES(?,?,?,?,?,?)", propertyId, body.roomTypeId(), body.roomNo(), body.floorNo(), body.status() == null ? "AVAILABLE" : body.status(), body.remark());
    return Map.of("code","0","message","房间创建成功");
  }

  @PutMapping("/rooms/{id}")
  public Map<String, Object> updateRoom(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody RoomRequest body) {
    guard(authorization, "room:manage");
    jdbc.update("UPDATE room SET room_type_id=?, room_no=?, floor_no=?, status=?, remark=? WHERE id=?",
        body.roomTypeId(), body.roomNo(), body.floorNo(), body.status() == null ? "AVAILABLE" : body.status(), body.remark(), id);
    return Map.of("code", "0", "message", "房间更新成功");
  }

  @PatchMapping("/rooms/{id}/status")
  public Map<String,Object> roomStatus(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable Long id, @RequestParam String status) { guard(authorization, "room:manage"); jdbc.update("UPDATE room SET status=? WHERE id=?", status, id); return Map.of("code","0","message","房态已更新"); }

  private void guard(String authorization, String permission) {
    session.requirePermission(authorization, permission);
  }
}
