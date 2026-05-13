package co.embracejoy.accounting.keystone.application.security;

import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tenant-scoped user role management. Called only from tenant-admin endpoints. Enforces the "can't
 * orphan self" rule: the lone Admin in a tenant cannot revoke or demote themselves.
 */
public final class UserRoleService {

  private final TenantUserRoleRepository repository;
  private final Clock clock;

  public UserRoleService(TenantUserRoleRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Result<TenantUserRole, SecurityError> grant(
      TenantId tenantId, String userSub, Role newRole, String grantedBy) {
    if (newRole != Role.ADMIN && grantedBy.equals(userSub)) {
      Optional<TenantUserRole> currentlyHeld = repository.findRole(tenantId, userSub);
      if (currentlyHeld.isPresent()
          && currentlyHeld.get().role() == Role.ADMIN
          && repository.countAdmins(tenantId) <= 1) {
        return Result.failure(new SecurityError.CannotOrphanSelf(tenantId, userSub));
      }
    }
    TenantUserRole row = new TenantUserRole(tenantId, userSub, newRole, clock.instant(), grantedBy);
    return Result.success(repository.grant(row));
  }

  public List<TenantUserRole> findByTenant(TenantId tenantId) {
    return repository.findByTenant(tenantId);
  }

  public Optional<TenantUserRole> findRole(TenantId tenantId, String userSub) {
    return repository.findRole(tenantId, userSub);
  }

  public Result<Void, SecurityError> revoke(TenantId tenantId, String userSub, String revokedBy) {
    Optional<TenantUserRole> existing = repository.findRole(tenantId, userSub);
    if (existing.isEmpty()) {
      return Result.failure(new SecurityError.RoleNotFound(tenantId, userSub));
    }
    if (existing.get().role() == Role.ADMIN
        && revokedBy.equals(userSub)
        && repository.countAdmins(tenantId) <= 1) {
      return Result.failure(new SecurityError.CannotOrphanSelf(tenantId, userSub));
    }
    repository.revoke(tenantId, userSub);
    return Result.success(null);
  }
}
