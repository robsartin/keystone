package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PHASE B/C INTERIM STUB: populates {@link TenantContext} with the default tenant on every HTTP
 * request, so the rest of the application's persistence layer (which reads {@code TenantContext} on
 * every query/insert) keeps working before authentication is wired.
 *
 * <p>Phase C deletes this filter and replaces it with {@code JwtTenantConverter}, which derives the
 * tenant from the JWT custom claim instead of always returning the default.
 */
@Component
@Order(0)
public class DefaultTenantFilter extends OncePerRequestFilter {

  private final TenantContext tenantContext;

  public DefaultTenantFilter(TenantContext tenantContext) {
    this.tenantContext = tenantContext;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    tenantContext.set(Tenants.DEFAULT_TENANT_ID);
    chain.doFilter(req, res);
  }
}
