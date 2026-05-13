package co.embracejoy.accounting.keystone.application.tenancy;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Use-case service for tenant CRUD. Called only from platform-admin endpoints; persistence uses the
 * {@code BYPASSRLS}-grant'd {@code DataSource}.
 */
public final class TenantService {

  private final TenantRepository repository;
  private final Clock clock;
  private final Supplier<UUID> uuidSupplier;

  public TenantService(TenantRepository repository, Clock clock, Supplier<UUID> uuidSupplier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
  }

  public Result<Tenant, TenantError> create(String name) {
    if (name == null || name.isBlank()) {
      return Result.failure(new TenantError.InvalidName("name must not be blank"));
    }
    Tenant t =
        new Tenant(new TenantId(uuidSupplier.get()), name, clock.instant(), Optional.empty());
    return repository.save(t);
  }

  public Optional<Tenant> findById(TenantId id) {
    return repository.findById(id);
  }

  public List<Tenant> findAll() {
    return repository.findAll();
  }

  public Result<Tenant, TenantError> deactivate(TenantId id) {
    return repository.deactivate(id);
  }
}
