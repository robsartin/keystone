package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link TenantUserRoleRepository}.
 *
 * <p>The {@code tenant_user_roles} table is RLS-protected. Every operation calls {@link
 * RlsTransactionInterceptor#applyToCurrentTransaction()} to set the {@code app.current_tenant} GUC
 * so RLS policies match.
 *
 * <p>The adapter also validates that the {@code tenantId} argument on each call matches the {@link
 * TenantContext}; a mismatch is a programming error and throws loudly.
 */
@Repository
@Transactional
public class TenantUserRoleRepositoryAdapter implements TenantUserRoleRepository {

  private final JpaTenantUserRoleRepository jpa;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public TenantUserRoleRepositoryAdapter(
      JpaTenantUserRoleRepository jpa,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jpa = jpa;
    this.tenantContext = tenantContext;
    this.rlsInterceptor = rlsInterceptor;
  }

  @Override
  public TenantUserRole grant(TenantUserRole assignment) {
    validateTenantMatch(assignment.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    TenantUserRoleEntity saved = jpa.save(TenantUserRoleEntityMapper.toEntity(assignment));
    return TenantUserRoleEntityMapper.toDomain(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantUserRole> findRole(TenantId tenantId, String userSub) {
    validateTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findByTenantIdAndUserSub(tenantId.value(), userSub)
        .map(TenantUserRoleEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TenantUserRole> findByTenant(TenantId tenantId) {
    validateTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findAllByTenantIdOrderByGrantedAtAsc(tenantId.value()).stream()
        .map(TenantUserRoleEntityMapper::toDomain)
        .toList();
  }

  @Override
  public boolean revoke(TenantId tenantId, String userSub) {
    validateTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.deleteByTenantIdAndUserSub(tenantId.value(), userSub) > 0;
  }

  @Override
  @Transactional(readOnly = true)
  public long countAdmins(TenantId tenantId) {
    validateTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.countByTenantIdAndRole(tenantId.value(), Role.ADMIN.name());
  }

  private void validateTenantMatch(TenantId argTenantId) {
    TenantId ctxTenantId = tenantContext.require();
    if (!ctxTenantId.equals(argTenantId)) {
      throw new IllegalStateException(
          "tenant mismatch — argument is " + argTenantId + ", context is " + ctxTenantId);
    }
  }
}
