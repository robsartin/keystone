package co.embracejoy.accounting.keystone.domain.tenancy;

import java.util.Objects;
import java.util.UUID;

/** Typed wrapper around the {@link UUID} primary key of a {@code Tenant}. */
public record TenantId(UUID value) {

  public TenantId {
    Objects.requireNonNull(value, "value");
  }
}
