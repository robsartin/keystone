package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code UiSecurityConfig}'s real chain needs a populated {@code ClientRegistrationRepository} and
 * {@code AuthenticationTenantResolver} against real JPA repositories — neither belongs in a
 * {@code @WebMvcTest} slice for this controller. This test instead imports a bare
 * {@code @EnableWebSecurity @EnableMethodSecurity} config with a trivial permit-all {@code
 * SecurityFilterChain}: the filter chain itself grants nothing, but it's what loads the {@code
 * SecurityContext} that {@code oidcLogin()} seeds into the mock session onto the request thread,
 * which is what {@code @PreAuthorize}'s method interceptor actually reads. (Disabling filters
 * entirely, per the brief's original suggestion, was tried first and found to leave {@code
 * SecurityContextHolder} empty during interceptor evaluation — see task-6-report.md.)
 */
@WebMvcTest(UserRoleUiController.class)
@Import(UserRoleUiControllerTest.SecurityTestConfig.class)
@DisplayName("UserRoleUiController")
class UserRoleUiControllerTest {

  @TestConfiguration
  @EnableWebSecurity
  @EnableMethodSecurity
  static class SecurityTestConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean UserRoleService service;
  @MockitoBean TenantContext tenantContext;

  @BeforeEach
  void setupTenant() {
    Mockito.when(tenantContext.require()).thenReturn(Tenants.DEFAULT_TENANT_ID);
  }

  @Test
  @DisplayName("GET /admin/ui/users renders list for tenant admin")
  void shouldRenderUserList() throws Exception {
    Mockito.when(service.findByTenant(Tenants.DEFAULT_TENANT_ID))
        .thenReturn(
            List.of(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|alice",
                    Role.BOOKKEEPER,
                    Instant.EPOCH,
                    "system")));

    mvc.perform(
            get("/admin/ui/users")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|alice")))
        .andExpect(content().string(containsString("BOOKKEEPER")));
  }

  @Test
  @DisplayName("GET /admin/ui/users renders empty state when tenant has no users")
  void shouldRenderEmptyStateWhenNoUsers() throws Exception {
    Mockito.when(service.findByTenant(Tenants.DEFAULT_TENANT_ID)).thenReturn(List.of());

    mvc.perform(
            get("/admin/ui/users")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No users yet.")));
  }

  /**
   * This slice's trivial permit-all {@code SecurityFilterChain} (see {@link SecurityTestConfig})
   * still runs {@code AnonymousAuthenticationFilter}, so an unauthenticated caller reaches
   * {@code @PreAuthorize} as an authenticated-but-role-less anonymous principal — the interceptor
   * rejects with 403 ({@code AuthorizationDeniedException}), not 401. A true "no session at all"
   * 401 only happens further out, at the real {@code UiSecurityConfig} chain (which redirects to
   * {@code /admin/ui/login} before the request ever reaches this controller) — that's covered by
   * {@code OAuth2LoginFlowIT}, not this slice.
   */
  @Test
  @DisplayName("GET /admin/ui/users returns 403 when caller has no ADMIN role")
  void shouldReturn403WhenUnauthenticated() throws Exception {
    mvc.perform(get("/admin/ui/users")).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("POST /admin/ui/users grants role and returns user-row fragment")
  void shouldAddUser() throws Exception {
    Mockito.when(
            service.grant(
                Tenants.DEFAULT_TENANT_ID, "auth0|bob", Role.BOOKKEEPER, "auth0|test-admin"))
        .thenReturn(
            Result.success(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|bob",
                    Role.BOOKKEEPER,
                    Instant.EPOCH,
                    "auth0|test-admin")));

    mvc.perform(
            post("/admin/ui/users")
                .param("userSub", "auth0|bob")
                .param("role", "BOOKKEEPER")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|bob")))
        .andExpect(content().string(containsString("BOOKKEEPER")));
  }

  @Test
  @DisplayName("PUT /admin/ui/users/{sub} changes role and returns user-row fragment")
  void shouldChangeRole() throws Exception {
    Mockito.when(
            service.grant(Tenants.DEFAULT_TENANT_ID, "auth0|bob", Role.ADMIN, "auth0|test-admin"))
        .thenReturn(
            Result.success(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|bob",
                    Role.ADMIN,
                    Instant.EPOCH,
                    "auth0|test-admin")));

    mvc.perform(
            put("/admin/ui/users/auth0|bob")
                .param("role", "ADMIN")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|bob")))
        .andExpect(content().string(containsString("ADMIN")));
  }

  @Test
  @DisplayName("DELETE /admin/ui/users/{sub} returns 200 empty body on success")
  void shouldRevokeUser() throws Exception {
    Mockito.when(service.revoke(Tenants.DEFAULT_TENANT_ID, "auth0|bob", "auth0|test-admin"))
        .thenReturn(Result.success(null));

    mvc.perform(
            delete("/admin/ui/users/auth0|bob")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("DELETE /admin/ui/users/{sub} returns 404 alert fragment when role is missing")
  void shouldReturn404WhenRevokeMissing() throws Exception {
    Mockito.when(service.revoke(Tenants.DEFAULT_TENANT_ID, "auth0|ghost", "auth0|test-admin"))
        .thenReturn(
            Result.failure(
                new SecurityError.RoleNotFound(Tenants.DEFAULT_TENANT_ID, "auth0|ghost")));

    mvc.perform(
            delete("/admin/ui/users/auth0|ghost")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("User role not found")));
  }

  @Test
  @DisplayName("PUT /admin/ui/users/{sub} returns 400 alert fragment when orphaning the lone admin")
  void shouldReturn400WhenPutOrphansSelf() throws Exception {
    Mockito.when(
            service.grant(
                Tenants.DEFAULT_TENANT_ID, "auth0|test-admin", Role.BOOKKEEPER, "auth0|test-admin"))
        .thenReturn(
            Result.failure(
                new SecurityError.CannotOrphanSelf(Tenants.DEFAULT_TENANT_ID, "auth0|test-admin")));

    mvc.perform(
            put("/admin/ui/users/auth0|test-admin")
                .param("role", "BOOKKEEPER")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Cannot orphan the lone tenant admin")));
  }

  @Test
  @DisplayName("POST /admin/ui/users returns 403 when caller only has BOOKKEEPER role")
  void shouldReturn403WhenBookkeeperPosts() throws Exception {
    mvc.perform(
            post("/admin/ui/users")
                .header("HX-Request", "true")
                .param("userSub", "auth0|bob")
                .param("role", "BOOKKEEPER")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_BOOKKEEPER"))
                        .idToken(t -> t.claim("sub", "auth0|test-bookkeeper"))))
        .andExpect(status().isForbidden());

    Mockito.verifyNoInteractions(service);
  }

  /**
   * Regression test for the T9 review finding: unrestricted {@code @RestControllerAdvice} on {@code
   * SecurityExceptionHandler} shadowed {@code UiExceptionHandler} for every controller, not just
   * {@code @RestController}s, so UI callers denied by {@code @PreAuthorize} got a JSON {@code
   * application/problem+json} body instead of this HTML alert fragment. Asserting the response
   * content type and body (not just status, as {@link #shouldReturn403WhenBookkeeperPosts} does) is
   * what proves {@code UiExceptionHandler.onAccessDenied} is the handler that actually fired. Sets
   * {@code HX-Request: true} to match this endpoint's real caller — the tenant-user-add form's
   * {@code hx-post} — which is what makes the handler pick the bare fragment view rather than the
   * full {@code error} page (T11's {@code AdminUiE2EIT} exercises that non-HTMX branch instead).
   */
  @Test
  @DisplayName("POST /admin/ui/users with BOOKKEEPER returns HTML alert fragment, not JSON")
  void shouldReturnHtmlAlertFragmentWhenAccessDenied() throws Exception {
    mvc.perform(
            post("/admin/ui/users")
                .header("HX-Request", "true")
                .param("userSub", "auth0|bob")
                .param("role", "BOOKKEEPER")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_BOOKKEEPER"))
                        .idToken(t -> t.claim("sub", "auth0|test-bookkeeper"))))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("Not allowed")));
  }
}
