package co.embracejoy.accounting.keystone.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Wires the browser-facing OAuth2 login filter chain for the admin UI, per Slice 5 Phase
 * D-admin-ui.
 *
 * <p>{@code @Order(2)} — evaluated after the embedded SAS's chains
 * ({@code @Order(0)}/{@code @Order(1)}, {@code dev}/{@code test} only) and before {@link
 * SecurityConfig}'s stateless bearer-JWT chain ({@code @Order(3)}). {@link #uiFilterChain} only
 * matches {@code /admin/ui/**}, the OAuth2 authorization-request redirect path, the OAuth2 login
 * callback path, and {@code /logout}; every other request (including the API's bearer-JWT surface)
 * falls through to {@code SecurityConfig}'s chain instead.
 *
 * <p>The OIDC user service delegates to Spring's default {@link OidcUserService} to fetch/validate
 * the ID token and userinfo, then hands the resulting {@code OidcUser} to {@link
 * AuthenticationTenantResolver} to derive tenant-scoped authorities and populate {@link
 * TenantContext} — mirroring what {@link JwtTenantConverter} does for the bearer-token flow. That
 * callback only fires once, at login; {@link UiTenantContextFilter} re-populates {@link
 * TenantContext} from the cached {@link Authentication} on every subsequent request in this chain,
 * since {@link TenantContext} is {@code @RequestScope} but the session-based {@code Authentication}
 * is cached for the life of the session.
 *
 * <p>{@code @Profile({"dev", "test", "prod"})} mirrors the profile gate on {@code
 * application.yaml}'s {@code spring.security.oauth2.client.registration.keystone} block (see T1's
 * comment there): this chain's {@code oauth2Login()} needs a populated {@code
 * ClientRegistrationRepository}, which only exists on those profiles. Without this gate, any boot
 * on an unconfigured profile — e.g. the {@code local} profile the {@code pre-integration-test}
 * Maven phase always starts the app under — fails with {@code NoSuchBeanDefinitionException} for
 * {@code ClientRegistrationRepository}, and profile-less {@code @WebMvcTest} slices would fail the
 * same way.
 */
@Configuration
@Profile({"dev", "test", "prod"})
public class UiSecurityConfig {

  @Bean
  @Order(2)
  public SecurityFilterChain uiFilterChain(
      HttpSecurity http, AuthenticationTenantResolver tenantResolver) throws Exception {
    OidcUserService delegate = new OidcUserService();
    var loginEntry = new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keystone");
    var htmxEntry = new HtmxAuthenticationEntryPoint("/admin/ui/login", loginEntry);

    http.securityMatcher(
            "/admin/ui/**", "/oauth2/authorization/**", "/login/oauth2/code/**", "/logout")
        .authorizeHttpRequests(
            a -> a.requestMatchers("/admin/ui/login").permitAll().anyRequest().authenticated())
        .oauth2Login(
            o ->
                o.userInfoEndpoint(
                        u ->
                            u.oidcUserService(
                                req -> {
                                  var user = delegate.loadUser(req);
                                  var authorities = tenantResolver.resolve(user);
                                  return new DefaultOidcUser(
                                      authorities, user.getIdToken(), user.getUserInfo(), "sub");
                                }))
                    .defaultSuccessUrl("/admin/ui/users", true))
        .logout(l -> l.logoutSuccessUrl("/admin/ui/login").permitAll())
        .exceptionHandling(e -> e.authenticationEntryPoint(htmxEntry))
        .csrf(
            c ->
                c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .addFilterAfter(
            new UiTenantContextFilter(tenantResolver), SecurityContextHolderFilter.class);

    return http.build();
  }
}
