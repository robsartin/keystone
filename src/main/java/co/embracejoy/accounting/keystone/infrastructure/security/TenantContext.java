package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped holder for the tenant of the current request.
 *
 * <p>Populated by {@code DefaultTenantFilter} during Phase B's interim unauthenticated state
 * (always returns the default tenant). Phase C replaces the filter with {@code JwtTenantConverter},
 * which derives the tenant from the JWT custom claim.
 *
 * <p>Repository adapters call {@link #require()} and use the returned {@link TenantId} as a filter
 * on every query. The {@code RlsTransactionInterceptor} reads {@link #current()} to set the
 * Postgres {@code app.current_tenant} GUC at the start of each transaction so RLS policies enforce
 * isolation at the database level too.
 */
@Component
@RequestScope
public class TenantContext {

  private TenantId tenantId;

  /** Set the current tenant. Idempotent — overwrites any previous value. */
  public void set(TenantId tenantId) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
  }

  /**
   * Return the current tenant; throws {@link IllegalStateException} if no tenant has been set for
   * the current request.
   */
  public TenantId require() {
    if (tenantId == null) {
      throw new IllegalStateException("no tenant in current request context");
    }
    return tenantId;
  }

  /** Return the current tenant if set; never throws. */
  public Optional<TenantId> current() {
    return Optional.ofNullable(tenantId);
  }
}
