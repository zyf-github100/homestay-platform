package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private static final Set<String> ROOM_OPERABLE_STATUSES = Set.of("AVAILABLE", "DIRTY", "MAINTENANCE", "DISABLED");

  private final JdbcTemplate jdbc;
  private final SessionService session;
  private final AuditService audit;

  public AdminController(JdbcTemplate jdbc, SessionService session) {
    this(jdbc, session, null);
  }

  @Autowired
  public AdminController(JdbcTemplate jdbc, SessionService session, AuditService audit) {
    this.jdbc = jdbc;
    this.session = session;
    this.audit = audit;
  }

  public record RoomTypeRequest(@NotBlank String name, String description, @NotBlank String bedType,
      @PositiveOrZero BigDecimal area, @NotNull @Positive Integer maxGuests,
      @NotNull @PositiveOrZero BigDecimal price, @Size(max = 500) String coverImage) {}
  public record RoomRequest(@NotNull Long roomTypeId, @NotBlank String roomNo, String floorNo, String status, String remark) {}
  public record PropertySettingsRequest(@NotBlank String name, String description, @NotBlank String address,
      String phone, String checkInTime, String checkOutTime, String checkInRule, String checkOutRule,
      @NotBlank String status) {}
  public record ImageRequest(@NotBlank @Size(max = 500) String imageUrl, String altText, Integer sortOrder) {}
  public record FacilityRequest(@NotBlank String name, Integer sortOrder) {}
  public record MediaRequest(List<@Valid ImageRequest> images, List<@Valid FacilityRequest> facilities) {}
  public record RoomOperationRequest(@NotBlank String status, String reason) {}

  @GetMapping("/room-types")
  public Map<String, Object> roomTypes(@RequestHeader(value = "Authorization", required = false) String authorization) {
    guard(authorization, "room-type:view");
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, name, description, bed_type bedType, area_sqm area, max_guests maxGuests, base_price price, cover_image coverImage, status FROM room_type ORDER BY id DESC");
    rows.forEach(row -> {
      Long roomTypeId = ((Number) row.get("id")).longValue();
      row.put("images", jdbc.queryForList("SELECT id, image_url imageUrl, alt_text altText, sort_order sortOrder FROM room_type_image WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
      row.put("facilities", jdbc.queryForList("SELECT id, name, sort_order sortOrder FROM room_type_facility WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
    });
    return Map.of("code", "0", "message", "success", "data", rows);
  }

  @PostMapping("/room-types")
  public Map<String, Object> createRoomType(@RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody RoomTypeRequest body) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    Long propertyId = propertyId();
    jdbc.update("INSERT INTO room_type(property_id,name,description,bed_type,area_sqm,max_guests,base_price,cover_image) VALUES(?,?,?,?,?,?,?,?)",
        propertyId, body.name().trim(), body.description(), body.bedType().trim(), body.area(), body.maxGuests(), body.price(), safeImageUrl(body.coverImage()));
    Long roomTypeId = jdbc.queryForObject("SELECT id FROM room_type WHERE property_id=? AND name=? ORDER BY id DESC LIMIT 1", Long.class, propertyId, body.name().trim());
    recordAudit(operatorId, "ROOM_TYPE_CREATE", "ROOM_TYPE", roomTypeId, "创建房型");
    return Map.of("code", "0", "message", "房型创建成功", "data", Map.of("id", roomTypeId));
  }

  @PutMapping("/room-types/{id}")
  public Map<String, Object> updateRoomType(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody RoomTypeRequest body) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    jdbc.update("UPDATE room_type SET name=?,description=?,bed_type=?,area_sqm=?,max_guests=?,base_price=?,cover_image=? WHERE id=?",
        body.name().trim(), body.description(), body.bedType().trim(), body.area(), body.maxGuests(), body.price(), safeImageUrl(body.coverImage()), id);
    recordAudit(operatorId, "ROOM_TYPE_UPDATE", "ROOM_TYPE", id, "更新房型");
    return Map.of("code", "0", "message", "房型更新成功");
  }

  @PatchMapping("/room-types/{id}/status")
  public Map<String, Object> roomTypeStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @RequestParam String status) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    if (!Set.of("PUBLISHED", "DRAFT").contains(status)) throw badRequest("房型状态不合法");
    jdbc.update("UPDATE room_type SET status=? WHERE id=?", status, id);
    recordAudit(operatorId, "ROOM_TYPE_STATUS", "ROOM_TYPE", id, "更新房型状态: " + status);
    return Map.of("code", "0", "message", "状态已更新");
  }

  @GetMapping({"/property", "/property/settings"})
  public Map<String, Object> property(@RequestHeader(value = "Authorization", required = false) String authorization) {
    guard(authorization, "property:view");
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, description, address, phone, check_in_time checkInTime, check_out_time checkOutTime, check_in_rule checkInRule, check_out_rule checkOutRule, status FROM property ORDER BY id LIMIT 1");
    if (rows.isEmpty()) throw notFound("民宿资料不存在");
    return Map.of("code", "0", "message", "success", "data", rows.getFirst());
  }

  @PutMapping({"/property", "/property/settings"})
  public Map<String, Object> updateProperty(@RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody PropertySettingsRequest body) {
    Long operatorId = session.requirePermission(authorization, "property:manage").id();
    if (!Set.of("OPEN", "CLOSED").contains(body.status())) throw badRequest("营业状态不合法");
    Long propertyId = propertyId();
    jdbc.update("UPDATE property SET name=?, description=?, address=?, phone=?, check_in_time=?, check_out_time=?, check_in_rule=?, check_out_rule=?, status=? WHERE id=?",
        body.name().trim(), body.description(), body.address().trim(), body.phone(), timeOrDefault(body.checkInTime(), "14:00:00"),
        timeOrDefault(body.checkOutTime(), "12:00:00"), body.checkInRule(), body.checkOutRule(), body.status(), propertyId);
    recordAudit(operatorId, "PROPERTY_UPDATE", "PROPERTY", propertyId, "更新民宿资料");
    return Map.of("code", "0", "message", "民宿资料已保存");
  }

  @GetMapping("/room-types/{id}/media")
  public Map<String, Object> media(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id) {
    guard(authorization, "room-type:view");
    ensureRoomType(id);
    return Map.of("code", "0", "message", "success", "data", mediaData(id));
  }

  @PutMapping("/room-types/{id}/media")
  @Transactional
  public Map<String, Object> replaceMedia(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody MediaRequest body) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    jdbc.update("DELETE FROM room_type_image WHERE room_type_id=?", id);
    jdbc.update("DELETE FROM room_type_facility WHERE room_type_id=?", id);
    if (body.images() != null) body.images().forEach(image -> insertImage(id, image));
    if (body.facilities() != null) body.facilities().forEach(facility -> insertFacility(id, facility));
    recordAudit(operatorId, "ROOM_TYPE_MEDIA_REPLACE", "ROOM_TYPE", id, "更新房型图片和设施");
    return Map.of("code", "0", "message", "房型图片和设施已保存", "data", mediaData(id));
  }

  @PostMapping("/room-types/{id}/images")
  public Map<String, Object> addImage(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody ImageRequest body) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    insertImage(id, body);
    recordAudit(operatorId, "ROOM_TYPE_IMAGE_ADD", "ROOM_TYPE", id, "新增房型图片");
    return Map.of("code", "0", "message", "图片已添加", "data", mediaData(id));
  }

  @DeleteMapping("/room-types/{id}/images/{imageId}")
  public Map<String, Object> deleteImage(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @PathVariable Long imageId) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    jdbc.update("DELETE FROM room_type_image WHERE id=? AND room_type_id=?", imageId, id);
    recordAudit(operatorId, "ROOM_TYPE_IMAGE_DELETE", "ROOM_TYPE", id, "删除房型图片");
    return Map.of("code", "0", "message", "图片已删除", "data", mediaData(id));
  }

  @PostMapping("/room-types/{id}/facilities")
  public Map<String, Object> addFacility(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody FacilityRequest body) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    insertFacility(id, body);
    recordAudit(operatorId, "ROOM_TYPE_FACILITY_ADD", "ROOM_TYPE", id, "新增房型设施");
    return Map.of("code", "0", "message", "设施已添加", "data", mediaData(id));
  }

  @DeleteMapping("/room-types/{id}/facilities/{facilityId}")
  public Map<String, Object> deleteFacility(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @PathVariable Long facilityId) {
    Long operatorId = session.requirePermission(authorization, "room-type:manage").id();
    ensureRoomType(id);
    jdbc.update("DELETE FROM room_type_facility WHERE id=? AND room_type_id=?", facilityId, id);
    recordAudit(operatorId, "ROOM_TYPE_FACILITY_DELETE", "ROOM_TYPE", id, "删除房型设施");
    return Map.of("code", "0", "message", "设施已删除", "data", mediaData(id));
  }

  @GetMapping("/rooms")
  public Map<String, Object> rooms(@RequestHeader(value = "Authorization", required = false) String authorization) {
    guard(authorization, "room:view");
    return Map.of("code", "0", "message", "success", "data", jdbc.queryForList("SELECT r.id, r.room_type_id roomTypeId, r.room_no roomNo, r.floor_no floorNo, r.status, r.remark, rt.name roomTypeName FROM room r JOIN room_type rt ON rt.id=r.room_type_id ORDER BY r.id DESC"));
  }

  @PostMapping("/rooms")
  public Map<String, Object> createRoom(@RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody RoomRequest body) {
    Long operatorId = session.requirePermission(authorization, "room:manage").id();
    Long propertyId = roomTypeProperty(body.roomTypeId());
    String status = normalizeRoomStatus(body.status());
    jdbc.update("INSERT INTO room(property_id,room_type_id,room_no,floor_no,status,remark) VALUES(?,?,?,?,?,?)",
        propertyId, body.roomTypeId(), body.roomNo().trim(), body.floorNo(), status, body.remark());
    Long roomId = jdbc.queryForObject("SELECT id FROM room WHERE property_id=? AND room_no=? ORDER BY id DESC LIMIT 1", Long.class, propertyId, body.roomNo().trim());
    recordAudit(operatorId, "ROOM_CREATE", "ROOM", roomId, "创建实体房间");
    return Map.of("code", "0", "message", "房间创建成功", "data", Map.of("id", roomId));
  }

  @PutMapping("/rooms/{id}")
  @Transactional
  public Map<String, Object> updateRoom(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody RoomRequest body) {
    Long operatorId = session.requirePermission(authorization, "room:manage").id();
    String status = normalizeRoomStatus(body.status());
    roomTypeProperty(body.roomTypeId());
    Map<String, Object> current = room(id);
    validateRoomStatus(String.valueOf(current.get("status")), status, id);
    jdbc.update("UPDATE room SET room_type_id=?, room_no=?, floor_no=?, status=?, remark=? WHERE id=?",
        body.roomTypeId(), body.roomNo().trim(), body.floorNo(), status, body.remark(), id);
    recordAudit(operatorId, "ROOM_UPDATE", "ROOM", id, "更新实体房间");
    return Map.of("code", "0", "message", "房间更新成功");
  }

  @PatchMapping("/rooms/{id}/status")
  @Transactional
  public Map<String, Object> roomStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @RequestParam String status,
      @RequestParam(required = false, defaultValue = "") String reason) {
    Long operatorId = session.requirePermission(authorization, "room:operate").id();
    return changeRoomStatus(operatorId, id, status, reason);
  }

  @PostMapping("/rooms/{id}/operations")
  @Transactional
  public Map<String, Object> roomOperation(@RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable Long id, @Valid @RequestBody RoomOperationRequest body) {
    Long operatorId = session.requirePermission(authorization, "room:operate").id();
    return changeRoomStatus(operatorId, id, body.status(), body.reason());
  }

  private Map<String, Object> changeRoomStatus(Long operatorId, Long id, String requestedStatus, String reason) {
    requestedStatus = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
    if (!ROOM_OPERABLE_STATUSES.contains(requestedStatus)) throw badRequest("房务状态只能是 AVAILABLE、DIRTY、MAINTENANCE 或 DISABLED");
    Map<String, Object> current = room(id);
    String currentStatus = String.valueOf(current.get("status"));
    validateRoomStatus(currentStatus, requestedStatus, id);
    jdbc.update("UPDATE room SET status=?, remark=CASE WHEN ?='' THEN remark ELSE ? END WHERE id=?", requestedStatus, reason == null ? "" : reason.trim(), reason == null ? "" : reason.trim(), id);
    recordAudit(operatorId, "ROOM_STATUS_CHANGE", "ROOM", id, currentStatus + " -> " + requestedStatus + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
    return Map.of("code", "0", "message", "房态已更新", "data", Map.of("roomId", id, "status", requestedStatus));
  }

  private Map<String, Object> mediaData(Long roomTypeId) {
    return Map.of("images", jdbc.queryForList("SELECT id, image_url imageUrl, alt_text altText, sort_order sortOrder FROM room_type_image WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId),
        "facilities", jdbc.queryForList("SELECT id, name, sort_order sortOrder FROM room_type_facility WHERE room_type_id=? ORDER BY sort_order, id", roomTypeId));
  }

  private void insertImage(Long roomTypeId, ImageRequest image) {
    jdbc.update("INSERT INTO room_type_image(room_type_id,image_url,alt_text,sort_order) VALUES(?,?,?,?)",
        roomTypeId, safeImageUrl(image.imageUrl()), image.altText(), image.sortOrder() == null ? 0 : image.sortOrder());
  }

  private String safeImageUrl(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String value = raw.trim();
    if (value.length() > 500 || value.chars().anyMatch(Character::isWhitespace)
        || !(value.startsWith("https://") || value.startsWith("http://")
            || value.startsWith("/assets/") || value.startsWith("/uploads/"))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片地址仅支持 HTTP(S) 或站内静态资源路径");
    }
    return value;
  }

  private void insertFacility(Long roomTypeId, FacilityRequest facility) {
    jdbc.update("INSERT INTO room_type_facility(room_type_id,name,sort_order) VALUES(?,?,?)",
        roomTypeId, facility.name().trim(), facility.sortOrder() == null ? 0 : facility.sortOrder());
  }

  private Long propertyId() {
    List<Long> ids = jdbc.queryForList("SELECT id FROM property ORDER BY id LIMIT 1", Long.class);
    if (ids.isEmpty()) throw notFound("民宿资料不存在");
    return ids.getFirst();
  }

  private Long roomTypeProperty(Long roomTypeId) {
    List<Long> ids = jdbc.queryForList("SELECT property_id FROM room_type WHERE id=?", Long.class, roomTypeId);
    if (ids.isEmpty()) throw notFound("房型不存在");
    return ids.getFirst();
  }

  private void ensureRoomType(Long id) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM room_type WHERE id=?", Integer.class, id);
    if (count == null || count == 0) throw notFound("房型不存在");
  }

  private Map<String, Object> room(Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status FROM room WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw notFound("房间不存在");
    return rows.getFirst();
  }

  private String normalizeRoomStatus(String status) {
    String value = status == null || status.isBlank() ? "AVAILABLE" : status.trim().toUpperCase();
    if (!ROOM_OPERABLE_STATUSES.contains(value)) throw badRequest("房间初始状态不合法");
    return value;
  }

  private void validateRoomStatus(String currentStatus, String requestedStatus, Long roomId) {
    if ("OCCUPIED".equals(currentStatus) && !"OCCUPIED".equals(requestedStatus)) throw conflict("在住房间请先办理退房");
    if ("RESERVED".equals(currentStatus) && Set.of("MAINTENANCE", "DISABLED").contains(requestedStatus)) throw conflict("已预订房间不能停用或维修");
    Integer locks = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_lock WHERE room_id=? AND (expires_at IS NULL OR expires_at>NOW())", Integer.class, roomId);
    if ("AVAILABLE".equals(requestedStatus) && locks != null && locks > 0) throw conflict("该房间已有预订库存");
  }

  private String timeOrDefault(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    if (!value.matches("^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$")) throw badRequest("时间格式应为 HH:mm 或 HH:mm:ss");
    return value.length() == 5 ? value + ":00" : value;
  }

  private void guard(String authorization, String permission) {
    session.requirePermission(authorization, permission);
  }

  private void recordAudit(Long operatorId, String action, String type, Long resourceId, String detail) {
    if (audit != null) audit.record(operatorId, action, type, resourceId == null ? null : String.valueOf(resourceId), "SUCCESS", detail);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
