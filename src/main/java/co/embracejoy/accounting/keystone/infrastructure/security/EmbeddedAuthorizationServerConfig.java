package co.embracejoy.accounting.keystone.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Embedded Spring Authorization Server, active only on {@code dev}/{@code test} profiles.
 *
 * <p>Lets local dev and integration tests walk the full OAuth2 authorization-code + PKCE handshake
 * against a real (if in-memory) IdP instead of requiring an external one. Never active in {@code
 * prod} — production deployments point {@code KEYSTONE_ISSUER_URI} at a real IdP.
 *
 * <p>The {@code keystone-admin-ui} client is the only registered client (T3 wires the UI to use
 * it). Three demo users (platform/admin/bookkeeper) authenticate via form login; {@link
 * #tenantClaimCustomizer()} maps their local usernames onto the {@code sas|*} subs seeded by {@link
 * DevUserSeeder} and stamps the tenant claim onto issued ID tokens.
 */
@Configuration
@Profile({"dev", "test"})
public class EmbeddedAuthorizationServerConfig {

  static final String TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";

  private static final Map<String, String> USER_TENANTS =
      Map.of(
          "sas|platform", Tenants.DEFAULT_TENANT_UUID.toString(),
          "sas|admin", Tenants.DEFAULT_TENANT_UUID.toString(),
          "sas|bookkeeper", Tenants.DEFAULT_TENANT_UUID.toString());

  @Bean
  public AuthorizationServerSettings authorizationServerSettings() {
    return AuthorizationServerSettings.builder().build();
  }

  @Bean
  public RegisteredClientRepository registeredClientRepository() {
    // SAS enforces exact-match on redirect_uri (no wildcards on host per OAuth2
    // spec). Register all ports the client can reach us from: default 8080
    // (dev docker-compose + maven-plugin openapi-snapshot boot), 18080 (test
    // ITs like OAuth2LoginFlowIT — Maven's integration-test phase pins 8080).
    RegisteredClient adminUi =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("keystone-admin-ui")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8080/login/oauth2/code/keystone")
            .redirectUri("http://localhost:18080/login/oauth2/code/keystone")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).build())
            .build();
    return new InMemoryRegisteredClientRepository(adminUi);
  }

  @Bean
  public UserDetailsService sasUsers() {
    return new InMemoryUserDetailsManager(
        List.of(
            User.withUsername("platform@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build(),
            User.withUsername("admin@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build(),
            User.withUsername("bookkeeper@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build()));
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair kp = kpg.generateKeyPair();
    RSAKey rsa =
        new RSAKey.Builder((RSAPublicKey) kp.getPublic())
            .privateKey((RSAPrivateKey) kp.getPrivate())
            .keyID(UUID.randomUUID().toString())
            .build();
    return new ImmutableJWKSet<>(new JWKSet(rsa));
  }

  /**
   * Required by {@code .oidc(Customizer.withDefaults())}: the OIDC userinfo endpoint validates its
   * bearer access token as a resource server, which needs a {@link JwtDecoder} bean. {@link
   * SecurityConfig#jwtDecoder()} deliberately yields no real bean when no external issuer is
   * configured (the dev/test default) — this bean fills that gap for the SAS's own tokens, backed
   * by the same in-memory key as {@link #jwkSource()}.
   *
   * <p>Named distinctly from {@code SecurityConfig#jwtDecoder()} (rather than reusing the {@code
   * jwtDecoder} bean name) to avoid a {@code BeanDefinitionOverrideException}: both configurations
   * would otherwise register a bean under the same name, and Spring's default {@code
   * allow-bean-definition-overriding=false} forbids that regardless of {@code @Configuration}
   * processing order. {@code SecurityConfig#jwtDecoder()}'s {@code @ConditionalOnMissingBean(
   * JwtDecoder.class)} still matches this bean by type, so the distinct name doesn't affect that
   * conditional.
   */
  @Bean
  public JwtDecoder sasJwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }

  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> tenantClaimCustomizer() {
    return context -> {
      String username = context.getPrincipal().getName();
      String sub =
          switch (username) {
            case "platform@keystone.local" -> "sas|platform";
            case "admin@keystone.local" -> "sas|admin";
            case "bookkeeper@keystone.local" -> "sas|bookkeeper";
            default -> username;
          };
      context.getClaims().subject(sub);
      String tenant = USER_TENANTS.get(sub);
      if (tenant != null) {
        context.getClaims().claim(TENANT_CLAIM, tenant);
      }
    };
  }

  /**
   * Serves the SAS's own {@code /login} form page.
   *
   * <p>{@link #authorizationServerFilterChain}'s {@code .formLogin(Customizer.withDefaults())} only
   * applies within that chain's {@code securityMatcher(configurer.getEndpointsMatcher())} — the
   * OAuth2 protocol endpoints ({@code /oauth2/authorize}, {@code /oauth2/token}, etc.). {@code
   * /login} itself is not one of those endpoints, so without this separate chain the
   * unauthenticated redirect an {@code /oauth2/authorize} request triggers has nowhere to land: the
   * SAS chain would 401/404 on {@code /login} instead of rendering the credential form.
   * {@code @Order(0)} — evaluated before every other chain in this JVM, though in practice its
   * matcher is disjoint from all of them.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain sasFormLoginFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/login")
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .formLogin(Customizer.withDefaults())
        .csrf(Customizer.withDefaults());
    return http.build();
  }

  /**
   * {@code @Order(1)} — evaluated after {@link #sasFormLoginFilterChain} ({@code @Order(0)}),
   * before {@code UiSecurityConfig}'s browser-facing chain ({@code @Order(2)}) and {@code
   * SecurityConfig}'s bearer-JWT chain ({@code @Order(3)}).
   */
  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
    http.securityMatcher(configurer.getEndpointsMatcher())
        .with(configurer, c -> c.oidc(Customizer.withDefaults()))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .csrf(c -> c.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
        .formLogin(Customizer.withDefaults());
    return http.build();
  }
}
