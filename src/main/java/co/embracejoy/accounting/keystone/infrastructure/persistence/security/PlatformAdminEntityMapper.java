package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdmin;

final class PlatformAdminEntityMapper {

  private PlatformAdminEntityMapper() {
    // static utility class; no instances
  }

  static PlatformAdminEntity toEntity(PlatformAdmin a) {
    return new PlatformAdminEntity(a.userSub(), a.grantedAt());
  }

  static PlatformAdmin toDomain(PlatformAdminEntity e) {
    return new PlatformAdmin(e.getUserSub(), e.getGrantedAt());
  }
}
