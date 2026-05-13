package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformAdmin")
class PlatformAdminTest {

  private static final Instant GRANTED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with userSub and grantedAt")
  void shouldConstruct() {
    PlatformAdmin p = new PlatformAdmin("auth0|root", GRANTED);
    assertEquals("auth0|root", p.userSub());
    assertEquals(GRANTED, p.grantedAt());
  }

  @Test
  @DisplayName("rejects blank userSub")
  void shouldThrowWhenUserSubBlank() {
    assertThrows(IllegalArgumentException.class, () -> new PlatformAdmin("", GRANTED));
  }

  @Test
  @DisplayName("rejects null grantedAt")
  void shouldThrowWhenGrantedAtNull() {
    assertThrows(NullPointerException.class, () -> new PlatformAdmin("u", null));
  }
}
