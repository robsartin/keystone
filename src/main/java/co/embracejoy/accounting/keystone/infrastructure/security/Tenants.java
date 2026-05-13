package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.UUID;

/**
 * Single source of truth for the default-tenant UUID generated in the V6 migration.
 *
 * <p>Standalone single-tenant deployments use this tenant for all data. Multi-tenant SaaS
 * deployments may leave it unused (harmless) or rename it via the platform-admin API once Phase D
 * ships.
 */
public final class Tenants {

  /**
   * Matches the literal in {@code V6__tenancy_and_rbac_tables.sql} and (in Phase C) the configured
   * {@code keystone.default-tenant-id}.
   */
  public static final UUID DEFAULT_TENANT_UUID =
      UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1");

  /** Typed wrapper for {@link #DEFAULT_TENANT_UUID}. */
  public static final TenantId DEFAULT_TENANT_ID = new TenantId(DEFAULT_TENANT_UUID);

  private Tenants() {
    // static constant class; no instances
  }
}
