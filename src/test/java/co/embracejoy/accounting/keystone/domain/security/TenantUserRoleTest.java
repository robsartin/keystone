package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantUserRole")
class TenantUserRoleTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final Instant GRANTED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with tenant, sub, role, grantedAt, grantedBy")
  void shouldConstruct() {
    TenantUserRole r =
        new TenantUserRole(TENANT, "auth0|user", Role.BOOKKEEPER, GRANTED, "auth0|admin");
    assertEquals(TENANT, r.tenantId());
    assertEquals("auth0|user", r.userSub());
    assertEquals(Role.BOOKKEEPER, r.role());
    assertEquals(GRANTED, r.grantedAt());
    assertEquals("auth0|admin", r.grantedBy());
  }

  @Test
  @DisplayName("rejects null tenantId")
  void shouldThrowWhenTenantNull() {
    assertThrows(
        NullPointerException.class, () -> new TenantUserRole(null, "u", Role.ADMIN, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects blank userSub")
  void shouldThrowWhenUserSubBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantUserRole(TENANT, " ", Role.ADMIN, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects null role")
  void shouldThrowWhenRoleNull() {
    assertThrows(
        NullPointerException.class, () -> new TenantUserRole(TENANT, "u", null, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects blank grantedBy")
  void shouldThrowWhenGrantedByBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantUserRole(TENANT, "u", Role.ADMIN, GRANTED, ""));
  }
}
