package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * One user's role within one tenant. Persisted in {@code tenant_user_roles} with composite primary
 * key {@code (tenant_id, user_sub)}.
 */
public record TenantUserRole(
    TenantId tenantId, String userSub, Role role, Instant grantedAt, String grantedBy) {

  public TenantUserRole {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(userSub, "userSub");
    if (userSub.isBlank()) {
      throw new IllegalArgumentException("userSub must not be blank");
    }
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(grantedAt, "grantedAt");
    Objects.requireNonNull(grantedBy, "grantedBy");
    if (grantedBy.isBlank()) {
      throw new IllegalArgumentException("grantedBy must not be blank");
    }
  }
}
