package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Derives Spring Security authorities and populates the request-scoped {@link TenantContext} from
 * an authenticated OIDC/OAuth2 user, mirroring {@link JwtTenantConverter}'s bearer-token logic for
 * the browser login flow wired by {@link UiSecurityConfig}.
 *
 * <p>Unlike {@link JwtTenantConverter}, an unresolvable tenant here does not throw. The OIDC
 * userinfo callback isn't the bearer-token surface — throwing {@code InvalidBearerTokenException}
 * (a resource-server exception type) from inside {@code oauth2Login()}'s user service would be
 * semantically wrong and would surface as an opaque OAuth2 login failure. Instead the caller ends
 * up authenticated with no tenant-scoped authority; {@code @PreAuthorize} on controllers then
 * rejects with a 403, which the UI's exception handler renders as a normal alert fragment.
 */
@Component
public class AuthenticationTenantResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticationTenantResolver.class);

  private final KeystoneSecurityProperties props;
  private final TenantRepository tenants;
  private final TenantUserRoleRepository roles;
  private final PlatformAdminRepository platformAdmins;
  private final TenantContext tenantContext;

  public AuthenticationTenantResolver(
      KeystoneSecurityProperties props,
      TenantRepository tenants,
      TenantUserRoleRepository roles,
      PlatformAdminRepository platformAdmins,
      TenantContext tenantContext) {
    this.props = props;
    this.tenants = tenants;
    this.roles = roles;
    this.platformAdmins = platformAdmins;
    this.tenantContext = tenantContext;
  }

  /**
   * Resolve authorities for {@code user}: {@code ROLE_PLATFORM_ADMIN} when the subject is a
   * platform admin, plus {@code ROLE_<name>} when the tenant claim resolves to a known tenant and
   * the subject holds a role there. An unknown, malformed, or absent tenant claim yields no tenant
   * authority and leaves {@link TenantContext} unset rather than failing authentication.
   */
  public Collection<GrantedAuthority> resolve(OAuth2User user) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    String sub = subFrom(user);
    if (platformAdmins.exists(sub)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    Object claim = user.getAttribute(props.tenantClaim());
    if (claim != null) {
      resolveTenant(claim.toString(), sub, authorities);
    }
    return authorities;
  }

  private void resolveTenant(String claim, String sub, Collection<GrantedAuthority> authorities) {
    UUID tenantUuid;
    try {
      tenantUuid = UUID.fromString(claim);
    } catch (IllegalArgumentException e) {
      LOG.warn("tenant claim '{}' is not a valid UUID for sub {}", claim, sub);
      return;
    }

    TenantId tenantId = new TenantId(tenantUuid);
    if (tenants.findById(tenantId).isEmpty()) {
      LOG.warn("unknown tenant {} for sub {}", tenantId, sub);
      return;
    }

    tenantContext.set(tenantId);
    roles
        .findRole(tenantId, sub)
        .ifPresent(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.role().name())));
  }

  private static String subFrom(OAuth2User user) {
    if (user instanceof OidcUser oidc) {
      return oidc.getSubject();
    }
    Object sub = user.getAttribute("sub");
    return sub == null ? user.getName() : sub.toString();
  }
}
