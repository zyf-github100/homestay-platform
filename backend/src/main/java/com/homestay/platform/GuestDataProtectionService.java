package com.homestay.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GuestDataProtectionService {
  private static final int IV_LENGTH = 12;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public GuestDataProtectionService(@Value("${guest.id-encryption-key}") String keyMaterial) {
    byte[] bytes = keyMaterial.getBytes(StandardCharsets.UTF_8);
    if (bytes.length != 32) throw new IllegalArgumentException("guest.id-encryption-key must be 32 bytes");
    this.key = new SecretKeySpec(bytes, "AES");
  }

  public ProtectedId protect(String plainText) {
    if (plainText == null || plainText.isBlank()) throw new IllegalArgumentException("证件号码不能为空");
    try {
      byte[] iv = new byte[IV_LENGTH];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(plainText.trim().getBytes(StandardCharsets.UTF_8));
      ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
      return new ProtectedId(Base64.getEncoder().encodeToString(payload.array()), hash(plainText), mask(plainText));
    } catch (Exception error) {
      throw new IllegalStateException("证件信息加密失败", error);
    }
  }

  public String reveal(String ciphertext) {
    try {
      byte[] payload = Base64.getDecoder().decode(ciphertext);
      byte[] iv = new byte[IV_LENGTH];
      byte[] encrypted = new byte[payload.length - IV_LENGTH];
      System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
      System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception error) {
      throw new IllegalStateException("证件信息解密失败", error);
    }
  }

  public String hash(String plainText) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(plainText.trim().getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte value : digest) result.append(String.format("%02x", value));
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }

  public String mask(String plainText) {
    String value = plainText.trim();
    if (value.length() <= 4) return "*".repeat(value.length());
    if (value.length() <= 8) return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
  }

  public record ProtectedId(String ciphertext, String hash, String masked) {}
}
