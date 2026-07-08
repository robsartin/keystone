package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@DisplayName("AuthenticationTenantResolver")
class AuthenticationTenantResolverTest {

  private static final String TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";
  private static final String SUB = "sas|alice";
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  private TenantRepository tenants;
  private TenantUserRoleRepository roles;
  private PlatformAdminRepository platformAdmins;
  private TenantContext tenantContext;
  private AuthenticationTenantResolver resolver;

  @BeforeEach
  void setup() {
    KeystoneSecurityProperties props =
        new KeystoneSecurityProperties(
            "https://issuer.example.com", "keystone-api", TENANT_CLAIM, "");
    tenants = mock(TenantRepository.class);
    roles = mock(TenantUserRoleRepository.class);
    platformAdmins = mock(PlatformAdminRepository.class);
    tenantContext = new TenantContext();
    resolver =
        new AuthenticationTenantResolver(props, tenants, roles, platformAdmins, tenantContext);

    when(platformAdmins.exists(SUB)).thenReturn(false);
    when(roles.findRole(TENANT, SUB)).thenReturn(Optional.empty());
  }

  private static OidcUser oidcUserWithTenantClaim(String tenantClaimValue) {
    OidcUser user = mock(OidcUser.class);
    when(user.getSubject()).thenReturn(SUB);
    when(user.getAttribute(TENANT_CLAIM)).thenReturn(tenantClaimValue);
    return user;
  }

  @Test
  @DisplayName("adds ROLE_PLATFORM_ADMIN when platformAdmins.exists(sub) is true")
  void shouldAddPlatformAdminAuthorityWhenExists() {
    when(platformAdmins.exists(SUB)).thenReturn(true);
    OidcUser user = oidcUserWithTenantClaim(null);

    Collection<GrantedAuthority> authorities = resolver.resolve(user);

    assertThat(authorities).extracting(Object::toString).contains("ROLE_PLATFORM_ADMIN");
  }

  @Test
  @DisplayName("reads the tenant claim and looks up the tenant via TenantRepository")
  void shouldLookUpTenantFromClaim() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    OidcUser user = oidcUserWithTenantClaim(TENANT.value().toString());

    resolver.resolve(user);

    verify(tenants).findById(TENANT);
  }

  @Test
  @DisplayName("returns empty authorities and leaves TenantContext unset when tenant is unknown")
  void shouldReturnEmptyAuthoritiesWhenTenantNotFound() {
    when(tenants.findById(TENANT)).thenReturn(Optional.empty());
    OidcUser user = oidcUserWithTenantClaim(TENANT.value().toString());

    Collection<GrantedAuthority> authorities = resolver.resolve(user);

    assertThat(authorities).isEmpty();
    assertThat(tenantContext.current()).isEmpty();
    verifyNoInteractions(roles);
  }

  @Test
  @DisplayName("returns empty authorities on a malformed tenant claim instead of throwing")
  void shouldReturnEmptyAuthoritiesWhenClaimIsMalformedUuid() {
    OidcUser user = oidcUserWithTenantClaim("not-a-uuid");

    Collection<GrantedAuthority> authorities = resolver.resolve(user);

    assertThat(authorities).isEmpty();
    assertThat(tenantContext.current()).isEmpty();
  }

  @Test
  @DisplayName("populates TenantContext when the tenant claim resolves to a known tenant")
  void shouldPopulateTenantContextWhenTenantValid() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    OidcUser user = oidcUserWithTenantClaim(TENANT.value().toString());

    resolver.resolve(user);

    assertThat(tenantContext.require()).isEqualTo(TENANT);
  }

  @Test
  @DisplayName("adds ROLE_<name> authority when tenant_user_roles has a matching row")
  void shouldAddRoleAuthorityWhenAssignmentExists() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    when(roles.findRole(TENANT, SUB))
        .thenReturn(
            Optional.of(new TenantUserRole(TENANT, SUB, Role.BOOKKEEPER, Instant.now(), "bob")));
    OidcUser user = oidcUserWithTenantClaim(TENANT.value().toString());

    Collection<GrantedAuthority> authorities = resolver.resolve(user);

    assertThat(authorities).extracting(Object::toString).contains("ROLE_BOOKKEEPER");
  }
}
