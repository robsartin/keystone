package co.embracejoy.accounting.keystone.infrastructure.web.admin.dto;

import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;

public record UserRoleResponse(String userSub, String role, String grantedAt, String grantedBy) {

  public static UserRoleResponse of(TenantUserRole r) {
    return new UserRoleResponse(
        r.userSub(), r.role().name(), r.grantedAt().toString(), r.grantedBy());
  }
}
