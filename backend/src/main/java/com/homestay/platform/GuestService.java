package com.homestay.platform;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GuestService {
  private final JdbcTemplate jdbc;
  private final GuestDataProtectionService protection;
  private final AuditService audit;

  public GuestService(JdbcTemplate jdbc, GuestDataProtectionService protection, AuditService audit) {
    this.jdbc = jdbc;
    this.protection = protection;
    this.audit = audit;
  }

  public List<Map<String, Object>> listMine(Long userId) {
    return jdbc.queryForList("SELECT id, name, gender, phone, id_type idType, id_no_masked idNoMasked, remark, created_at createdAt, updated_at updatedAt FROM guest_profile WHERE user_id=? AND deleted_at IS NULL ORDER BY id DESC", userId);
  }

  public List<Map<String, Object>> listAll() {
    return jdbc.queryForList("SELECT gp.id, gp.user_id userId, u.username, gp.name, gp.gender, gp.phone, gp.id_type idType, gp.id_no_masked idNoMasked, gp.remark, gp.created_at createdAt FROM guest_profile gp JOIN sys_user u ON u.id=gp.user_id WHERE gp.deleted_at IS NULL ORDER BY gp.id DESC");
  }

  @Transactional
  public Map<String, Object> create(Long userId, String name, String gender, String phone,
      String idType, String idNo, String remark) {
    GuestDataProtectionService.ProtectedId protectedId = protection.protect(idNo);
    jdbc.update("INSERT INTO guest_profile(user_id,name,gender,phone,id_type,id_no_ciphertext,id_no_hash,id_no_masked,remark) VALUES(?,?,?,?,?,?,?,?,?)",
        userId, name, gender, phone, defaultIdType(idType), protectedId.ciphertext(), protectedId.hash(), protectedId.masked(), remark);
    Long id = jdbc.queryForObject("SELECT id FROM guest_profile WHERE user_id=? AND id_no_hash=? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", Long.class, userId, protectedId.hash());
    audit.record(userId, "GUEST_CREATE", "GUEST_PROFILE", String.valueOf(id), "SUCCESS", "创建房客资料");
    return Map.of("id", id, "name", name, "gender", valueOrEmpty(gender), "phone", valueOrEmpty(phone), "idType", defaultIdType(idType), "idNoMasked", protectedId.masked(), "remark", valueOrEmpty(remark));
  }

  @Transactional
  public Map<String, Object> update(Long userId, Long id, String name, String gender, String phone,
      String idType, String idNo, String remark) {
    Map<String, Object> profile = ownedProfile(userId, id);
    String ciphertext = String.valueOf(profile.get("id_no_ciphertext"));
    String hash = String.valueOf(profile.get("id_no_hash"));
    String masked = String.valueOf(profile.get("id_no_masked"));
    if (idNo != null && !idNo.isBlank()) {
      GuestDataProtectionService.ProtectedId protectedId = protection.protect(idNo);
      ciphertext = protectedId.ciphertext(); hash = protectedId.hash(); masked = protectedId.masked();
    }
    jdbc.update("UPDATE guest_profile SET name=?,gender=?,phone=?,id_type=?,id_no_ciphertext=?,id_no_hash=?,id_no_masked=?,remark=? WHERE id=? AND user_id=? AND deleted_at IS NULL",
        name, gender, phone, defaultIdType(idType), ciphertext, hash, masked, remark, id, userId);
    audit.record(userId, "GUEST_UPDATE", "GUEST_PROFILE", String.valueOf(id), "SUCCESS", "更新房客资料");
    return Map.of("id", id, "name", name, "gender", valueOrEmpty(gender), "phone", valueOrEmpty(phone), "idType", defaultIdType(idType), "idNoMasked", masked, "remark", valueOrEmpty(remark));
  }

  @Transactional
  public void delete(Long userId, Long id) {
    ownedProfile(userId, id);
    jdbc.update("UPDATE guest_profile SET deleted_at=NOW() WHERE id=? AND user_id=? AND deleted_at IS NULL", id, userId);
    audit.record(userId, "GUEST_DELETE", "GUEST_PROFILE", String.valueOf(id), "SUCCESS", "删除房客资料");
  }

  public Map<String, Object> sensitive(Long operatorId, Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, user_id userId, name, gender, phone, id_type idType, id_no_ciphertext ciphertext, id_no_masked idNoMasked, remark FROM guest_profile WHERE id=? AND deleted_at IS NULL", id);
    if (rows.isEmpty()) throw notFound("房客资料不存在");
    Map<String, Object> profile = rows.getFirst();
    String plainId = protection.reveal(String.valueOf(profile.get("ciphertext")));
    audit.record(operatorId, "GUEST_SENSITIVE_VIEW", "GUEST_PROFILE", String.valueOf(id), "SUCCESS", "查看证件明文");
    return Map.of("id", profile.get("id"), "userId", profile.get("userId"), "name", profile.get("name"), "gender", valueOrEmpty(profile.get("gender")), "phone", valueOrEmpty(profile.get("phone")), "idType", profile.get("idType"), "idNoMasked", profile.get("idNoMasked"), "idNo", plainId, "remark", valueOrEmpty(profile.get("remark")));
  }

  private Map<String, Object> ownedProfile(Long userId, Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, id_no_ciphertext, id_no_hash, id_no_masked FROM guest_profile WHERE id=? AND user_id=? AND deleted_at IS NULL", id, userId);
    if (rows.isEmpty()) throw notFound("房客资料不存在");
    return rows.getFirst();
  }

  private String defaultIdType(String idType) { return idType == null || idType.isBlank() ? "ID_CARD" : idType.trim().toUpperCase(); }
  private String valueOrEmpty(Object value) { return value == null ? "" : String.valueOf(value); }
  private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
