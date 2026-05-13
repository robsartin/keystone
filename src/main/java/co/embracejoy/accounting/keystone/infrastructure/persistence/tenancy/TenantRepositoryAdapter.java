package co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link TenantRepository}.
 *
 * <p>The {@code tenants} table is intentionally NOT RLS-protected — platform admins need
 * cross-tenant visibility. Phase D moves this adapter to a dedicated platform-pool {@code
 * DataSource} (with {@code BYPASSRLS}); for B-infra it uses the default app pool, which is fine
 * because no RLS is on the table.
 */
@Repository
@Transactional
public class TenantRepositoryAdapter implements TenantRepository {

  private final JpaTenantRepository jpa;

  public TenantRepositoryAdapter(JpaTenantRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Result<Tenant, TenantError> save(Tenant tenant) {
    TenantEntity saved = jpa.save(TenantEntityMapper.toEntity(tenant));
    return Result.success(TenantEntityMapper.toDomain(saved));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Tenant> findById(TenantId id) {
    return jpa.findById(id.value()).map(TenantEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Tenant> findAll() {
    return jpa.findAllByOrderByCreatedAtAsc().stream().map(TenantEntityMapper::toDomain).toList();
  }

  @Override
  public Result<Tenant, TenantError> deactivate(TenantId id) {
    Optional<TenantEntity> opt = jpa.findById(id.value());
    if (opt.isEmpty()) {
      return Result.failure(new TenantError.NotFound(id));
    }
    TenantEntity entity = opt.get();
    entity.setDeactivatedAt(Instant.now());
    return Result.success(TenantEntityMapper.toDomain(jpa.save(entity)));
  }
}
