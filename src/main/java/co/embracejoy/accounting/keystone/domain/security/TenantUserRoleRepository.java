package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link TenantUserRole}. */
public interface TenantUserRoleRepository {

  /**
   * Insert or update the role. Idempotent: granting the same role to the same user is a no-op that
   * returns the existing row.
   */
  TenantUserRole grant(TenantUserRole assignment);

  /** Look up a user's role within a tenant. Returns {@code Optional.empty()} if no row. */
  Optional<TenantUserRole> findRole(TenantId tenantId, String userSub);

  /** All role assignments within a tenant, ordered by {@code grantedAt} ASC. */
  List<TenantUserRole> findByTenant(TenantId tenantId);

  /** Remove the user's role within the tenant. Returns true if a row was removed. */
  boolean revoke(TenantId tenantId, String userSub);

  /**
   * Count of users currently holding {@link Role#ADMIN} in this tenant. Used by {@code
   * UserRoleService} to enforce the can't-orphan-self rule.
   */
  long countAdmins(TenantId tenantId);
}
