package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

@DisplayName("JwtTenantConverter")
class JwtTenantConverterTest {

  private static final String TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";
  private static final String SUB = "auth0|alice";
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  private KeystoneSecurityProperties props;
  private TenantRepository tenants;
  private TenantUserRoleRepository roles;
  private PlatformAdminRepository platformAdmins;
  private TenantContext tenantContext;
  private JwtTenantConverter converter;

  @BeforeEach
  void setup() {
    props =
        new KeystoneSecurityProperties(
            "https://issuer.example.com", "keystone-api", TENANT_CLAIM, "");
    tenants = mock(TenantRepository.class);
    roles = mock(TenantUserRoleRepository.class);
    platformAdmins = mock(PlatformAdminRepository.class);
    tenantContext = new TenantContext();
    converter = new JwtTenantConverter(props, tenants, roles, platformAdmins, tenantContext);

    when(platformAdmins.exists(SUB)).thenReturn(false);
    when(roles.findRole(TENANT, SUB)).thenReturn(Optional.empty());
  }

  private static Jwt jwtWithTenantClaim(String tenantClaimValue) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token").header("alg", "none").subject(SUB).issuedAt(Instant.now());
    if (tenantClaimValue != null) {
      builder.claim(TENANT_CLAIM, tenantClaimValue);
    }
    return builder.build();
  }

  @Test
  @DisplayName("adds ROLE_PLATFORM_ADMIN when platformAdmins.exists(sub) is true")
  void shouldAddPlatformAdminAuthorityWhenExists() {
    when(platformAdmins.exists(SUB)).thenReturn(true);
    Jwt jwt = jwtWithTenantClaim(null);

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities()).extracting(Object::toString).contains("ROLE_PLATFORM_ADMIN");
  }

  @Test
  @DisplayName("reads the tenant claim and looks up the tenant via TenantRepository")
  void shouldLookUpTenantFromClaim() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    Jwt jwt = jwtWithTenantClaim(TENANT.value().toString());

    converter.convert(jwt);

    verify(tenants).findById(TENANT);
  }

  @Test
  @DisplayName(
      "throws InvalidBearerTokenException(\"unknown tenant\") when the tenant is not found")
  void shouldThrowWhenTenantNotFound() {
    when(tenants.findById(TENANT)).thenReturn(Optional.empty());
    Jwt jwt = jwtWithTenantClaim(TENANT.value().toString());

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("unknown tenant");
  }

  @Test
  @DisplayName("throws InvalidBearerTokenException(\"invalid tenant claim\") on malformed UUID")
  void shouldThrowWhenClaimIsMalformedUuid() {
    Jwt jwt = jwtWithTenantClaim("not-a-uuid");

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("invalid tenant claim");
  }

  @Test
  @DisplayName("populates TenantContext when the tenant claim resolves to a known tenant")
  void shouldPopulateTenantContextWhenTenantValid() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    Jwt jwt = jwtWithTenantClaim(TENANT.value().toString());

    converter.convert(jwt);

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
    Jwt jwt = jwtWithTenantClaim(TENANT.value().toString());

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token.getAuthorities()).extracting(Object::toString).contains("ROLE_BOOKKEEPER");
  }
}
