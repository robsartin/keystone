package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-populates the request-scoped {@link TenantContext} on every request handled by {@link
 * UiSecurityConfig}'s session-based OAuth2 login chain.
 *
 * <p>Spring Security's {@code oidcUserService} callback — where {@link
 * AuthenticationTenantResolver} normally runs — fires exactly once, at the {@code
 * /login/oauth2/code/**} callback. The resulting {@link Authentication} is then cached in the
 * {@code HttpSession} and replayed verbatim on every later request via {@code
 * SecurityContextHolderFilter}. {@link TenantContext}, however, is {@code @RequestScope}: each new
 * request starts with an empty instance, so without this filter {@code RlsTransactionInterceptor}
 * never issues {@code SET LOCAL app.current_tenant} on any request after login, and RLS silently
 * returns zero rows.
 *
 * <p>This filter re-derives the tenant from the already-authenticated principal on every request,
 * without re-deriving authorities — the cached {@link Authentication} already carries those from
 * login time.
 */
public class UiTenantContextFilter extends OncePerRequestFilter {

  private final AuthenticationTenantResolver resolver;

  public UiTenantContextFilter(AuthenticationTenantResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof OAuth2AuthenticationToken token
        && token.getPrincipal() instanceof OidcUser oidcUser) {
      resolver.resolve(oidcUser);
    }
    filterChain.doFilter(request, response);
  }
}
