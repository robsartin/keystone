package co.embracejoy.accounting.keystone.domain.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A tenant — the boundary of data isolation. Every business row carries a {@link TenantId}.
 *
 * <p>Soft delete: setting {@link #deactivatedAt()} marks the tenant as inactive without removing
 * its data. The composite foreign keys on {@code accounts}/{@code periods}/etc. mean a hard delete
 * would cascade through years of accounting data; that path is intentionally not exposed.
 */
public record Tenant(TenantId id, String name, Instant createdAt, Optional<Instant> deactivatedAt) {

  public Tenant {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(deactivatedAt, "deactivatedAt");
  }

  public boolean isActive() {
    return deactivatedAt.isEmpty();
  }

  public boolean isDeactivated() {
    return deactivatedAt.isPresent();
  }
}
