package co.embracejoy.accounting.keystone.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2 resource-server + tenancy-bootstrap configuration.
 *
 * <p>Bound to the {@code keystone.security} prefix in {@code application.yaml}:
 *
 * <pre>
 * keystone:
 *   security:
 *     issuer-uri: ${KEYSTONE_ISSUER_URI:}
 *     audience: ${KEYSTONE_AUDIENCE:}
 *     tenant-claim: "https://keystone.embracejoy.co/tenant_id"
 *     bootstrap-platform-admin-sub: ${KEYSTONE_PLATFORM_ADMIN_SUB:}
 * </pre>
 *
 * <p>Every field is nullable/optional so the app still boots in developer profiles where these
 * values are unset. Phase C-auth-config's {@code SecurityConfig} decides how to handle absent
 * values at boot time (fail-fast for {@code issuer-uri} + {@code audience} in production; permit
 * empty for tests). {@code tenantClaim} defaults to the namespaced URI per ADR-0017 so operators
 * only need to override it if their IdP emits the claim under a different name. {@code
 * bootstrapPlatformAdminSub} is the seed for the {@code PlatformAdminBootstrap} runner — empty
 * means "no bootstrap", non-empty means "insert this sub into platform_admins on startup".
 */
@ConfigurationProperties("keystone.security")
public record KeystoneSecurityProperties(
    String issuerUri, String audience, String tenantClaim, String bootstrapPlatformAdminSub) {

  private static final String DEFAULT_TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";

  public KeystoneSecurityProperties {
    if (tenantClaim == null || tenantClaim.isBlank()) {
      tenantClaim = DEFAULT_TENANT_CLAIM;
    }
    if (bootstrapPlatformAdminSub == null) {
      bootstrapPlatformAdminSub = "";
    }
  }
}
