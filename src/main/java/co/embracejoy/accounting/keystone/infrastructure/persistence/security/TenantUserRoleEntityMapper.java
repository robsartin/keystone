package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;

final class TenantUserRoleEntityMapper {

  private TenantUserRoleEntityMapper() {
    // static utility class; no instances
  }

  static TenantUserRoleEntity toEntity(TenantUserRole r) {
    return new TenantUserRoleEntity(
        r.tenantId().value(), r.userSub(), r.role().name(), r.grantedAt(), r.grantedBy());
  }

  static TenantUserRole toDomain(TenantUserRoleEntity e) {
    return new TenantUserRole(
        new TenantId(e.getTenantId()),
        e.getUserSub(),
        Role.valueOf(e.getRole()),
        e.getGrantedAt(),
        e.getGrantedBy());
  }
}
