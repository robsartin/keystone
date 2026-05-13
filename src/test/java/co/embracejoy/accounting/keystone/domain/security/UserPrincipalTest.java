package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserPrincipal")
class UserPrincipalTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final String SUB = "auth0|abc123";

  @Test
  @DisplayName("constructs with sub, tenantId, role, platformAdmin flag")
  void shouldConstruct() {
    UserPrincipal p = new UserPrincipal(SUB, Optional.of(TENANT), Optional.of(Role.ADMIN), false);
    assertEquals(SUB, p.sub());
    assertEquals(Optional.of(TENANT), p.tenantId());
    assertEquals(Optional.of(Role.ADMIN), p.role());
    assertEquals(false, p.platformAdmin());
  }

  @Test
  @DisplayName("rejects null sub")
  void shouldThrowWhenSubNull() {
    assertThrows(
        NullPointerException.class,
        () -> new UserPrincipal(null, Optional.of(TENANT), Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects blank sub")
  void shouldThrowWhenSubBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UserPrincipal("   ", Optional.of(TENANT), Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects null tenantId Optional")
  void shouldThrowWhenTenantOptionalNull() {
    assertThrows(
        NullPointerException.class,
        () -> new UserPrincipal(SUB, null, Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects null role Optional")
  void shouldThrowWhenRoleOptionalNull() {
    assertThrows(
        NullPointerException.class, () -> new UserPrincipal(SUB, Optional.of(TENANT), null, false));
  }

  @Test
  @DisplayName("platform-admin-only principal: no tenant, no role")
  void shouldAcceptPlatformAdminWithoutTenantOrRole() {
    UserPrincipal p = new UserPrincipal(SUB, Optional.empty(), Optional.empty(), true);
    assertTrue(p.platformAdmin());
  }
}
