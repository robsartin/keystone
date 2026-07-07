package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wires the OAuth2 resource-server filter chain per ADR-0017.
 *
 * <p>When {@code keystone.security.issuer-uri} is unset (the default in tests and unconfigured
 * local dev), the chain permits every request instead of validating a JWT — the app still boots and
 * the existing {@code @WebMvcTest} slices keep working. Production deployments must set {@code
 * KEYSTONE_ISSUER_URI} (and {@code KEYSTONE_AUDIENCE}) to turn auth on.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

  private final KeystoneSecurityProperties props;

  public SecurityConfig(KeystoneSecurityProperties props) {
    this.props = props;
  }

  @Bean
  JwtTenantConverter jwtTenantConverter(
      TenantRepository tenants,
      TenantUserRoleRepository roles,
      PlatformAdminRepository platformAdmins,
      TenantContext tenantContext) {
    return new JwtTenantConverter(props, tenants, roles, platformAdmins, tenantContext);
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JwtTenantConverter converter)
      throws Exception {
    boolean authEnabled = authEnabled();
    if (authEnabled) {
      LOG.info("auth ENABLED against issuer {}", props.issuerUri());
    } else {
      LOG.warn("auth DISABLED (no issuer configured) - every request accepted");
    }

    http.authorizeHttpRequests(
        a -> {
          a.requestMatchers(
                  "/actuator/health",
                  "/actuator/info",
                  "/v3/api-docs/**",
                  "/swagger-ui/**",
                  "/swagger-ui.html")
              .permitAll();
          if (authEnabled) {
            a.anyRequest().authenticated();
          } else {
            a.anyRequest().permitAll();
          }
        });

    if (authEnabled) {
      http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
    }

    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(CsrfConfigurer::disable)
        .build();
  }

  /**
   * Only wired when auth is enabled: {@link JwtDecoders#fromIssuerLocation} requires a non-blank
   * issuer, and nothing looks this bean up unless {@link #filterChain} wired the resource-server
   * DSL in the first place.
   */
  @Bean
  JwtDecoder jwtDecoder() {
    if (!authEnabled()) {
      return null;
    }
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(props.issuerUri());
    OAuth2TokenValidator<Jwt> validators =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(props.issuerUri()), audienceValidator());
    decoder.setJwtValidator(validators);
    return decoder;
  }

  private boolean authEnabled() {
    return props.issuerUri() != null && !props.issuerUri().isBlank();
  }

  private OAuth2TokenValidator<Jwt> audienceValidator() {
    return jwt ->
        jwt.getAudience() != null && jwt.getAudience().contains(props.audience())
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_audience", "expected " + props.audience(), null));
  }
}
