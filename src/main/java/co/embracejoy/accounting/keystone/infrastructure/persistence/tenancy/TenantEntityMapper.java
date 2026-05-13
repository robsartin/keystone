package co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy;

import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

final class TenantEntityMapper {

  private TenantEntityMapper() {
    // static utility class; no instances
  }

  static TenantEntity toEntity(Tenant t) {
    return new TenantEntity(
        t.id().value(), t.name(), t.createdAt(), t.deactivatedAt().orElse(null));
  }

  static Tenant toDomain(TenantEntity e) {
    return new Tenant(
        new TenantId(e.getId()),
        e.getName(),
        e.getCreatedAt(),
        Optional.ofNullable(e.getDeactivatedAt()));
  }
}
