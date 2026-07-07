package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Derives Spring Security authorities and populates the request-scoped {@link TenantContext} from a
 * validated JWT, per ADR-0017.
 *
 * <p>The subject ({@code sub}) claim is the IdP-issued user id. Platform admins (checked against
 * {@link PlatformAdminRepository}) get {@code ROLE_PLATFORM_ADMIN} regardless of tenant. When the
 * configured tenant claim is present, it must resolve to a known {@code Tenant}; the resolved
 * {@link TenantId} is pushed into {@link TenantContext} and the caller's role within that tenant
 * (from {@link TenantUserRoleRepository}) becomes a {@code ROLE_<name>} authority.
 *
 * <p>Not a Spring bean itself ({@code @Component} intentionally omitted) — {@code SecurityConfig}
 * constructs it explicitly so it can be wired into the resource-server filter chain.
 */
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final KeystoneSecurityProperties props;
  private final TenantRepository tenants;
  private final TenantUserRoleRepository roles;
  private final PlatformAdminRepository platformAdmins;
  private final TenantContext tenantContext;

  public JwtTenantConverter(
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

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String sub = jwt.getSubject();
    Collection<GrantedAuthority> authorities = new ArrayList<>();

    if (platformAdmins.exists(sub)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    String tenantClaim = jwt.getClaimAsString(props.tenantClaim());
    if (tenantClaim != null) {
      resolveTenant(tenantClaim, sub, authorities);
    }

    return new JwtAuthenticationToken(jwt, authorities, sub);
  }

  private void resolveTenant(
      String tenantClaim, String sub, Collection<GrantedAuthority> authorities) {
    TenantId tenantId = parseTenantId(tenantClaim);
    if (tenants.findById(tenantId).isEmpty()) {
      throw new InvalidBearerTokenException("unknown tenant");
    }
    tenantContext.set(tenantId);

    Optional<TenantUserRole> role = roles.findRole(tenantId, sub);
    role.ifPresent(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.role().name())));
  }

  private static TenantId parseTenantId(String tenantClaim) {
    try {
      return new TenantId(UUID.fromString(tenantClaim));
    } catch (IllegalArgumentException e) {
      throw new InvalidBearerTokenException("invalid tenant claim");
    }
  }
}
