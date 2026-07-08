package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Same {@code @WebMvcTest} slice shape as {@code UserRoleUiControllerTest}/{@code
 * TenantUiControllerTest}: a trivial {@code @EnableWebSecurity @EnableMethodSecurity} config with a
 * permit-all {@code SecurityFilterChain} loads the {@code SecurityContext} that {@code oidcLogin()}
 * seeds, which is what {@code SecurityContextHolder.getContext().getAuthentication()} reads inside
 * the controller.
 */
@WebMvcTest(ProfileUiController.class)
@Import(ProfileUiControllerTest.SecurityTestConfig.class)
@DisplayName("ProfileUiController")
class ProfileUiControllerTest {

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

  @Test
  @DisplayName("GET /admin/ui/profile renders sub, tenant id, and role")
  void shouldRenderProfile() throws Exception {
    Mockito.when(tenantContext.current()).thenReturn(Optional.of(Tenants.DEFAULT_TENANT_ID));
    Mockito.when(service.findRole(Tenants.DEFAULT_TENANT_ID, "auth0|alice"))
        .thenReturn(
            Optional.of(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|alice",
                    Role.BOOKKEEPER,
                    Instant.EPOCH,
                    "auth0|test-admin")));

    mvc.perform(
            get("/admin/ui/profile")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_BOOKKEEPER"))
                        .idToken(t -> t.claim("sub", "auth0|alice"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|alice")))
        .andExpect(content().string(containsString(Tenants.DEFAULT_TENANT_UUID.toString())))
        .andExpect(content().string(containsString("BOOKKEEPER")));
  }

  @Test
  @DisplayName("GET /admin/ui/profile renders \"(no role)\" when the caller has no tenant role")
  void shouldRenderNoRoleWhenNoTenantRole() throws Exception {
    Mockito.when(tenantContext.current()).thenReturn(Optional.of(Tenants.DEFAULT_TENANT_ID));
    Mockito.when(service.findRole(Tenants.DEFAULT_TENANT_ID, "auth0|ghost"))
        .thenReturn(Optional.empty());

    mvc.perform(
            get("/admin/ui/profile")
                .with(
                    SecurityMockMvcRequestPostProcessors.oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_BOOKKEEPER"))
                        .idToken(t -> t.claim("sub", "auth0|ghost"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("(no role)")));
  }
}
