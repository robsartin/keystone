package co.embracejoy.accounting.keystone.domain.tenancy;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Tenant}.
 *
 * <p>Wired in Phase B (Spring `BYPASSRLS` `DataSource` for the implementation; tenants table is
 * itself not RLS-protected).
 */
public interface TenantRepository {

  /** Persist a new tenant. Returns the saved aggregate. */
  Result<Tenant, TenantError> save(Tenant tenant);

  /** Look up a tenant by id. Returns Optional.empty() if not found (active or not). */
  Optional<Tenant> findById(TenantId id);

  /** All tenants (active + deactivated). Ordered by createdAt ASC. */
  List<Tenant> findAll();

  /** Soft-delete: sets {@code deactivatedAt} to now. Idempotent. */
  Result<Tenant, TenantError> deactivate(TenantId id);
}
