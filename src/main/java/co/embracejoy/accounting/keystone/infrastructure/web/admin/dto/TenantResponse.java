package co.embracejoy.accounting.keystone.infrastructure.web.admin.dto;

import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import java.time.Instant;

public record TenantResponse(String id, String name, String createdAt, String deactivatedAt) {

  public static TenantResponse of(Tenant t) {
    return new TenantResponse(
        t.id().value().toString(),
        t.name(),
        t.createdAt().toString(),
        t.deactivatedAt().map(Instant::toString).orElse(null));
  }
}
