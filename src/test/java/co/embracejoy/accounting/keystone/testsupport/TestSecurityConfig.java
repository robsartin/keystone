package co.embracejoy.accounting.keystone.testsupport;

import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Turns auth ON for tests: a non-blank, obviously-fake issuer + audience so {@code SecurityConfig}
 * enforces JWT auth per ADR-0017, a {@code JwtDecoder} that validates against a locally-held RSA
 * key pair (no IdP round-trip — see {@link JwtTestSupport}), and a real (not mocked) {@link
 * TenantContext} so the production {@code JwtTenantConverter} can populate it from the minted
 * token.
 *
 * <p>Import alongside {@code SecurityConfig} in {@code @WebMvcTest} slices and
 * {@code @SpringBootTest}s that need to exercise the full auth pipeline:
 *
 * <pre>{@code @Import({TestSecurityConfig.class, SecurityConfig.class})}</pre>
 */
@TestConfiguration
@EnableConfigurationProperties(KeystoneSecurityProperties.class)
public class TestSecurityConfig {

  /** Non-blank, obviously-fake issuer — no real IdP is ever contacted. */
  public static final String ISSUER = "https://test.keystone.local/issuer";

  /** Non-blank, obviously-fake audience. */
  public static final String AUDIENCE = "keystone-test-api";

  private static final String TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";

  // KeystoneSecurityProperties is bound via @EnableConfigurationProperties (class-level).
  // The values it binds to come from the enclosing test's @TestPropertySource — every test
  // that imports TestSecurityConfig sets these two matching properties:
  //   keystone.security.issuer-uri=https://test.keystone.local/issuer
  //   keystone.security.audience=keystone-test-api
  // See ISSUER + AUDIENCE constants below — they mirror those properties so JwtTestSupport
  // mints tokens the JwtDecoder will accept.

  @Bean
  JwtTestSupport jwtTestSupport() {
    return new JwtTestSupport(JwtTestSupport.generatedKey(), ISSUER, AUDIENCE, TENANT_CLAIM);
  }

  /**
   * {@code @Primary} plus {@code SecurityConfig.jwtDecoder()}'s {@code @ConditionalOnMissingBean}
   * together guarantee this bean — not a real OIDC-discovery decoder — backs the resource server.
   */
  @Bean
  @Primary
  JwtDecoder testJwtDecoder(JwtTestSupport jwtTestSupport) throws Exception {
    RSAPublicKey publicKey = (RSAPublicKey) jwtTestSupport.publicKey().toPublicKey();
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    OAuth2TokenValidator<Jwt> validators =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER), audienceValidator());
    decoder.setJwtValidator(validators);
    return decoder;
  }

  /**
   * Non-request-scoped fallback for {@code @WebMvcTest} slices where the {@code @Component} {@link
   * TenantContext} isn't auto-scanned. Skipped in {@code @SpringBootTest}s (which DO scan the real
   * request-scoped bean) so we don't override it and break request scoping.
   */
  @Bean
  @ConditionalOnMissingBean
  TenantContext tenantContext() {
    return new TenantContext();
  }

  private static OAuth2TokenValidator<Jwt> audienceValidator() {
    return jwt ->
        jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_audience", "expected " + AUDIENCE, null));
  }
}
