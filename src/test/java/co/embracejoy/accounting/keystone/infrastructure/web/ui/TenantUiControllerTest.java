package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.tenancy.TenantService;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * Same {@code @WebMvcTest} slice shape as {@code UserRoleUiControllerTest}: a trivial
 * {@code @EnableWebSecurity @EnableMethodSecurity} config with a permit-all {@code
 * SecurityFilterChain} loads the {@code SecurityContext} that {@code oidcLogin()} seeds, which is
 * what {@code @PreAuthorize}'s method interceptor reads. Unlike {@code UserRoleUiController}, this
 * controller has no {@code TenantContext} dependency — tenants are platform-scoped, not
 * tenant-scoped.
 */
@WebMvcTest(TenantUiController.class)
@Import(TenantUiControllerTest.SecurityTestConfig.class)
@DisplayName("TenantUiController")
class TenantUiControllerTest {

  private static final TenantId TENANT_ID =
      new TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

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
  @MockitoBean TenantService service;

  @Test
  @DisplayName("GET /admin/ui/tenants renders list for platform admin")
  void shouldRenderTenantList() throws Exception {
    Mockito.when(service.findAll())
        .thenReturn(List.of(new Tenant(TENANT_ID, "Acme Co", Instant.EPOCH, Optional.empty())));

    mvc.perform(
            get("/admin/ui/tenants")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Acme Co")));
  }

  @Test
  @DisplayName("GET /admin/ui/tenants renders empty state when there are no tenants")
  void shouldRenderEmptyState() throws Exception {
    Mockito.when(service.findAll()).thenReturn(List.of());

    mvc.perform(
            get("/admin/ui/tenants")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No tenants yet.")));
  }

  @Test
  @DisplayName("POST /admin/ui/tenants creates a tenant and returns the tenant-row fragment")
  void shouldCreateTenant() throws Exception {
    Mockito.when(service.create("Acme Co"))
        .thenReturn(
            Result.success(new Tenant(TENANT_ID, "Acme Co", Instant.EPOCH, Optional.empty())));

    mvc.perform(
            post("/admin/ui/tenants")
                .param("name", "Acme Co")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Acme Co")));
  }

  /**
   * A truly blank {@code name} would fail {@code CreateTenantForm}'s {@code @NotBlank} Bean
   * Validation before the request ever reaches this controller's body — and without a
   * {@code @ControllerAdvice} (that's T9's job), the resulting {@code
   * MethodArgumentNotValidException} wouldn't render our alert fragment. So this test posts a form
   * value that passes Bean Validation, and exercises the ADR-0004 domain-error dispatch path by
   * mocking {@code TenantService#create} to fail with {@code TenantError.InvalidName} directly.
   */
  @Test
  @DisplayName(
      "POST /admin/ui/tenants returns 400 alert fragment when the service rejects the name")
  void shouldReturn400WhenNameBlank() throws Exception {
    Mockito.when(service.create("x"))
        .thenReturn(Result.failure(new TenantError.InvalidName("name must not be blank")));

    mvc.perform(
            post("/admin/ui/tenants")
                .param("name", "x")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("name must not be blank")));
  }

  @Test
  @DisplayName("GET /admin/ui/tenants/{id} renders tenant detail")
  void shouldRenderTenantDetail() throws Exception {
    Mockito.when(service.findById(TENANT_ID))
        .thenReturn(Optional.of(new Tenant(TENANT_ID, "Acme Co", Instant.EPOCH, Optional.empty())));

    mvc.perform(
            get("/admin/ui/tenants/" + TENANT_ID.value())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Acme Co")));
  }

  @Test
  @DisplayName("POST /admin/ui/tenants/{id}/deactivate returns the updated tenant-row fragment")
  void shouldDeactivateTenant() throws Exception {
    Mockito.when(service.deactivate(TENANT_ID))
        .thenReturn(
            Result.success(
                new Tenant(TENANT_ID, "Acme Co", Instant.EPOCH, Optional.of(Instant.EPOCH))));

    mvc.perform(
            post("/admin/ui/tenants/" + TENANT_ID.value() + "/deactivate")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Acme Co")))
        .andExpect(content().string(containsString("Deactivated")));
  }

  @Test
  @DisplayName("GET /admin/ui/tenants/{id} returns 404 alert fragment when id is not a UUID")
  void shouldReturn404WhenGetByIdWithMalformedUuid() throws Exception {
    mvc.perform(
            get("/admin/ui/tenants/not-a-uuid")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("Tenant not found")));

    Mockito.verifyNoInteractions(service);
  }

  @Test
  @DisplayName("GET /admin/ui/tenants/{id} returns 404 alert when tenant does not exist")
  void shouldReturn404WhenGetByIdMissing() throws Exception {
    TenantId missing = new TenantId(UUID.fromString("01902f9f-0000-7000-8000-999999999999"));
    Mockito.when(service.findById(missing)).thenReturn(Optional.empty());

    mvc.perform(
            get("/admin/ui/tenants/01902f9f-0000-7000-8000-999999999999")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("Tenant not found")));
  }

  @Test
  @DisplayName("GET /admin/ui/tenants returns 403 when caller only has ADMIN role")
  void shouldReturn403WhenNonPlatformAdmin() throws Exception {
    mvc.perform(
            get("/admin/ui/tenants")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|tenant-admin"))))
        .andExpect(status().isForbidden());

    Mockito.verifyNoInteractions(service);
  }

  /**
   * Regression test for the T9 review finding: unrestricted {@code @RestControllerAdvice} on {@code
   * ValidationExceptionHandler} shadowed {@code UiExceptionHandler} for every controller, not just
   * {@code @RestController}s, so a Bean Validation failure on this {@code @Valid @ModelAttribute}
   * form-backing bean (no explicit {@code BindingResult} parameter, so Spring throws {@code
   * MethodArgumentNotValidException} rather than swallowing it into a {@code BindingResult})
   * rendered as JSON {@code application/problem+json} instead of this HTML alert fragment.
   */
  @Test
  @DisplayName("POST /admin/ui/tenants with blank name returns HTML alert fragment, not JSON")
  void shouldReturnHtmlAlertFragmentOnBeanValidationFailure() throws Exception {
    mvc.perform(
            post("/admin/ui/tenants")
                .param("name", "")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                        .idToken(t -> t.claim("sub", "auth0|platform-admin"))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("Invalid input")));
  }
}
