package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Objects;
import java.util.Optional;

/**
 * The authenticated user as observed by the application layer.
 *
 * <p>{@code sub} is the IdP-issued subject claim ({@code jwt.getSubject()}). {@code tenantId} is
 * the tenant from the JWT custom claim (empty if the user authenticated as a platform admin without
 * a tenant). {@code role} is the user's role within that tenant (empty if no row in {@code
 * tenant_user_roles}). {@code platformAdmin} is true if the {@code sub} is in the {@code
 * platform_admins} table.
 */
public record UserPrincipal(
    String sub, Optional<TenantId> tenantId, Optional<Role> role, boolean platformAdmin) {

  public UserPrincipal {
    Objects.requireNonNull(sub, "sub");
    if (sub.isBlank()) {
      throw new IllegalArgumentException("sub must not be blank");
    }
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(role, "role");
  }
}
