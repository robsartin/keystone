package co.embracejoy.accounting.keystone.domain.security;

import java.time.Instant;
import java.util.Objects;

/**
 * A user with cross-tenant authority. Stored in the {@code platform_admins} table (PK on {@code
 * user_sub}). Platform admins create tenants and grant the platform-admin role to other users via
 * SQL or the bootstrap env var; they do not get implicit access to tenant data — they need an
 * explicit role in {@code tenant_user_roles} for that.
 */
public record PlatformAdmin(String userSub, Instant grantedAt) {

  public PlatformAdmin {
    Objects.requireNonNull(userSub, "userSub");
    if (userSub.isBlank()) {
      throw new IllegalArgumentException("userSub must not be blank");
    }
    Objects.requireNonNull(grantedAt, "grantedAt");
  }
}
