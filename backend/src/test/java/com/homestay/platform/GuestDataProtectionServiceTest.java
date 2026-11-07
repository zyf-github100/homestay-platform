package com.homestay.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class GuestDataProtectionServiceTest {
  @Test
  void encryptsDecryptsAndMasksIdentityNumber() {
    GuestDataProtectionService protection = new GuestDataProtectionService("0123456789abcdef0123456789abcdef");

    GuestDataProtectionService.ProtectedId value = protection.protect("110101199001011234");

    assertFalse(value.ciphertext().contains("110101199001011234"));
    assertEquals("1101**********1234", value.masked());
    assertEquals("110101199001011234", protection.reveal(value.ciphertext()));
    assertEquals(64, value.hash().length());
  }
}
