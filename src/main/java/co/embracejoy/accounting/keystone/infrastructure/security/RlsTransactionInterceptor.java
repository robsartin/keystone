package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets the {@code app.current_tenant} Postgres GUC at the start of every tenant-scoped transaction,
 * so RLS policies can read the GUC and filter rows.
 *
 * <p>Repository adapters call {@link #applyToCurrentTransaction()} as the first statement of any
 * RLS-protected operation. {@code SET LOCAL} is scoped to the current transaction; it resets at
 * COMMIT or ROLLBACK, so connection-pool reuse is safe.
 *
 * <p>If {@link TenantContext} is unset (e.g., a non-HTTP path with no test setup), the GUC is not
 * set and RLS policies see {@code current_setting('app.current_tenant', true)} as NULL — which
 * makes every comparison false and returns zero rows. This is the intentional fail-closed behavior.
 */
@Component
public class RlsTransactionInterceptor {

  @PersistenceContext private EntityManager em;
  private final TenantContext tenantContext;

  public RlsTransactionInterceptor(TenantContext tenantContext) {
    this.tenantContext = tenantContext;
  }

  /**
   * Set the GUC for the current transaction. The {@code MANDATORY} propagation forces a
   * pre-existing transaction; callers must already be inside an {@code @Transactional} boundary.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void applyToCurrentTransaction() {
    tenantContext
        .current()
        .ifPresent(
            tid ->
                em.createNativeQuery("SET LOCAL app.current_tenant = '" + tid.value() + "'")
                    .executeUpdate());
  }
}
