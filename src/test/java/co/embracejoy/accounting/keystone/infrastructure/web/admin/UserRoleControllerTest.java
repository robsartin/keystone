package co.embracejoy.accounting.keystone.infrastructure.web.admin;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(UserRoleController.class)
@Import({TestSecurityConfig.class, SecurityConfig.class})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("UserRoleController")
class UserRoleControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean UserRoleService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  private static final String CALLER_SUB = "auth0|test-user";
  private static final String ALICE_SUB = "auth0|alice";
  private static final String ALICE_URL = "auth0|alice";
  private static final String CALLER_URL = "auth0|test-user";
  private static final Instant GRANTED_AT = Instant.parse("2026-02-01T00:00:00Z");

  @BeforeEach
  void setupAuth() {
    Mockito.when(tenants.findById(Tenants.DEFAULT_TENANT_ID))
        .thenReturn(
            Optional.of(
                new Tenant(
                    Tenants.DEFAULT_TENANT_ID, "Test Tenant", Instant.now(), Optional.empty())));
    Mockito.when(platformAdmins.exists(Mockito.anyString())).thenReturn(false);
    Mockito.when(roles.findRole(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
  }

  private RequestPostProcessor withTestAuth(Role callerRole) {
    Mockito.when(roles.findRole(Tenants.DEFAULT_TENANT_ID, CALLER_SUB))
        .thenReturn(
            Optional.of(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID, CALLER_SUB, callerRole, Instant.EPOCH, "system")));
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mint(CALLER_SUB, Tenants.DEFAULT_TENANT_ID));
      return req;
    };
  }

  private static TenantUserRole aRole(String sub, Role role) {
    return new TenantUserRole(Tenants.DEFAULT_TENANT_ID, sub, role, GRANTED_AT, "system");
  }

  @Test
  @DisplayName("GET /admin/users returns 200 with list of user roles")
  void shouldReturn200WithListOfUserRoles() throws Exception {
    Mockito.when(service.findByTenant(Tenants.DEFAULT_TENANT_ID))
        .thenReturn(List.of(aRole(ALICE_SUB, Role.ADMIN), aRole("auth0|bob", Role.BOOKKEEPER)));

    mvc.perform(get("/admin/users").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].userSub").value(ALICE_SUB))
        .andExpect(jsonPath("$[0].role").value("ADMIN"))
        .andExpect(jsonPath("$[1].userSub").value("auth0|bob"))
        .andExpect(jsonPath("$[1].role").value("BOOKKEEPER"));
  }

  @Test
  @DisplayName("GET /admin/users/{userSub} returns 200 when found")
  void shouldReturn200WhenGetUserRoleFound() throws Exception {
    Mockito.when(service.findRole(Tenants.DEFAULT_TENANT_ID, ALICE_SUB))
        .thenReturn(Optional.of(aRole(ALICE_SUB, Role.BOOKKEEPER)));

    mvc.perform(get("/admin/users/" + ALICE_URL).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userSub").value(ALICE_SUB))
        .andExpect(jsonPath("$.role").value("BOOKKEEPER"));
  }

  @Test
  @DisplayName("GET /admin/users/{userSub} returns 404 when not found")
  void shouldReturn404WhenGetUserRoleNotFound() throws Exception {
    Mockito.when(service.findRole(Tenants.DEFAULT_TENANT_ID, ALICE_SUB))
        .thenReturn(Optional.empty());

    mvc.perform(get("/admin/users/" + ALICE_URL).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/user-role-not-found")));
  }

  @Test
  @DisplayName("PUT /admin/users/{userSub} returns 200 when grant succeeds")
  void shouldReturn200WhenGrantSucceeds() throws Exception {
    Mockito.when(
            service.grant(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID),
                Mockito.eq(ALICE_SUB),
                Mockito.eq(Role.BOOKKEEPER),
                Mockito.anyString()))
        .thenReturn(Result.success(aRole(ALICE_SUB, Role.BOOKKEEPER)));

    mvc.perform(
            put("/admin/users/" + ALICE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"BOOKKEEPER"}
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userSub").value(ALICE_SUB))
        .andExpect(jsonPath("$.role").value("BOOKKEEPER"));
  }

  @Test
  @DisplayName("PUT /admin/users/{userSub} returns 400 when role value is invalid")
  void shouldReturn400WhenBeanValidationRejectsRoleValue() throws Exception {
    mvc.perform(
            put("/admin/users/" + ALICE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"BOSS"}
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("PUT /admin/users/{userSub} returns 400 when grant would orphan self")
  void shouldReturn400WhenGrantOrphansSelf() throws Exception {
    Mockito.when(
            service.grant(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID),
                Mockito.eq(CALLER_SUB),
                Mockito.eq(Role.READ_ONLY),
                Mockito.anyString()))
        .thenReturn(
            Result.failure(
                new SecurityError.CannotOrphanSelf(Tenants.DEFAULT_TENANT_ID, CALLER_SUB)));

    mvc.perform(
            put("/admin/users/" + CALLER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"READ_ONLY"}
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/cannot-orphan-self")));
  }

  @Test
  @DisplayName("DELETE /admin/users/{userSub} returns 204 when revoke succeeds")
  void shouldReturn204WhenRevokeSucceeds() throws Exception {
    Mockito.when(
            service.revoke(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID), Mockito.eq(ALICE_SUB), Mockito.anyString()))
        .thenReturn(Result.success(null));

    mvc.perform(delete("/admin/users/" + ALICE_URL).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("DELETE /admin/users/{userSub} returns 404 when role not found")
  void shouldReturn404WhenRevokeMissing() throws Exception {
    Mockito.when(
            service.revoke(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID), Mockito.eq(ALICE_SUB), Mockito.anyString()))
        .thenReturn(
            Result.failure(new SecurityError.RoleNotFound(Tenants.DEFAULT_TENANT_ID, ALICE_SUB)));

    mvc.perform(delete("/admin/users/" + ALICE_URL).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/user-role-not-found")));
  }

  @Test
  @DisplayName("DELETE /admin/users/{userSub} returns 400 when revoke would orphan self")
  void shouldReturn400WhenRevokeOrphansSelf() throws Exception {
    Mockito.when(
            service.revoke(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID), Mockito.eq(CALLER_SUB), Mockito.anyString()))
        .thenReturn(
            Result.failure(
                new SecurityError.CannotOrphanSelf(Tenants.DEFAULT_TENANT_ID, CALLER_SUB)));

    mvc.perform(delete("/admin/users/" + CALLER_URL).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/cannot-orphan-self")));
  }

  @Test
  @DisplayName("GET /admin/users returns 403 when caller has only BOOKKEEPER")
  void shouldReturn403WhenBookkeeperCallsList() throws Exception {
    mvc.perform(get("/admin/users").with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("PUT /admin/users/{userSub} returns 403 when caller has only READ_ONLY")
  void shouldReturn403WhenReadOnlyCallsGrant() throws Exception {
    mvc.perform(
            put("/admin/users/" + ALICE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"BOOKKEEPER"}
                    """)
                .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("GET /admin/users returns 401 when there is no Authorization header")
  void shouldReturn401WhenNoAuthHeader() throws Exception {
    mvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("PUT /admin/users/{userSub} passes caller sub as grantedBy")
  void shouldPassCallerSubAsGrantedByWhenGranting() throws Exception {
    Mockito.when(
            service.grant(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID),
                Mockito.eq(ALICE_SUB),
                Mockito.eq(Role.BOOKKEEPER),
                Mockito.anyString()))
        .thenReturn(Result.success(aRole(ALICE_SUB, Role.BOOKKEEPER)));

    mvc.perform(
            put("/admin/users/" + ALICE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"role":"BOOKKEEPER"}
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk());

    ArgumentCaptor<String> grantedBy = ArgumentCaptor.forClass(String.class);
    Mockito.verify(service)
        .grant(
            Mockito.eq(Tenants.DEFAULT_TENANT_ID),
            Mockito.eq(ALICE_SUB),
            Mockito.eq(Role.BOOKKEEPER),
            grantedBy.capture());
    org.assertj.core.api.Assertions.assertThat(grantedBy.getValue()).isEqualTo(CALLER_SUB);
  }
}
