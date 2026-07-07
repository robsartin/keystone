package co.embracejoy.accounting.keystone.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeystoneSecurityProperties")
class KeystoneSecurityPropertiesTest {

  @Test
  @DisplayName("preserves explicit values across all four fields")
  void shouldPreserveExplicitValues() {
    KeystoneSecurityProperties p =
        new KeystoneSecurityProperties(
            "https://issuer.example",
            "keystone-api",
            "https://custom.example/tenant",
            "auth0|root");
    assertThat(p.issuerUri()).isEqualTo("https://issuer.example");
    assertThat(p.audience()).isEqualTo("keystone-api");
    assertThat(p.tenantClaim()).isEqualTo("https://custom.example/tenant");
    assertThat(p.bootstrapPlatformAdminSub()).isEqualTo("auth0|root");
  }

  @Test
  @DisplayName("null tenantClaim defaults to the namespaced URI per ADR-0017")
  void shouldDefaultTenantClaim() {
    KeystoneSecurityProperties p = new KeystoneSecurityProperties(null, null, null, null);
    assertThat(p.tenantClaim()).isEqualTo("https://keystone.embracejoy.co/tenant_id");
  }

  @Test
  @DisplayName("blank tenantClaim defaults to the namespaced URI per ADR-0017")
  void shouldDefaultBlankTenantClaim() {
    KeystoneSecurityProperties p = new KeystoneSecurityProperties(null, null, "   ", null);
    assertThat(p.tenantClaim()).isEqualTo("https://keystone.embracejoy.co/tenant_id");
  }

  @Test
  @DisplayName("null bootstrapPlatformAdminSub becomes empty string (no-op signal)")
  void shouldDefaultBootstrapSubToEmpty() {
    KeystoneSecurityProperties p = new KeystoneSecurityProperties(null, null, null, null);
    assertThat(p.bootstrapPlatformAdminSub()).isEmpty();
  }

  @Test
  @DisplayName("null issuerUri + audience are preserved as null (validation is deferred)")
  void shouldPreserveNullIssuerAndAudience() {
    KeystoneSecurityProperties p = new KeystoneSecurityProperties(null, null, null, null);
    assertThat(p.issuerUri()).isNull();
    assertThat(p.audience()).isNull();
  }
}
