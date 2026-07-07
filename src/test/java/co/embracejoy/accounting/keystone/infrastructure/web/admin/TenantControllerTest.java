package co.embracejoy.accounting.keystone.infrastructure.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.tenancy.TenantService;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TenantController.class)
@Import({TestSecurityConfig.class, SecurityConfig.class})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("TenantController")
class TenantControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean TenantService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  private static final String PLATFORM_ADMIN_SUB = "auth0|platform-admin";
  private static final UUID TENANT_UUID = UUID.fromString("01902f9f-0000-7000-8000-000000000001");
  private static final TenantId TENANT_ID = new TenantId(TENANT_UUID);
  private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");

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

  private RequestPostProcessor withPlatformAdmin() {
    Mockito.when(platformAdmins.exists(PLATFORM_ADMIN_SUB)).thenReturn(true);
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mintWithoutTenant(PLATFORM_ADMIN_SUB));
      return req;
    };
  }

  private RequestPostProcessor withRoleOnly(Role role) {
    String sub = "auth0|tenant-user";
    Mockito.when(roles.findRole(Tenants.DEFAULT_TENANT_ID, sub))
        .thenReturn(
            Optional.of(
                new TenantUserRole(Tenants.DEFAULT_TENANT_ID, sub, role, Instant.EPOCH, "system")));
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mint(sub, Tenants.DEFAULT_TENANT_ID));
      return req;
    };
  }

  private static Tenant anActiveTenant() {
    return new Tenant(TENANT_ID, "Acme", CREATED_AT, Optional.empty());
  }

  @Test
  @DisplayName("POST /admin/tenants returns 201 + Location when service succeeds")
  void shouldReturn201WithLocationWhenCreateSucceeds() throws Exception {
    Mockito.when(service.create("Acme")).thenReturn(Result.success(anActiveTenant()));

    mvc.perform(
            post("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Acme"}
                    """)
                .with(withPlatformAdmin()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/admin/tenants/" + TENANT_UUID)))
        .andExpect(jsonPath("$.id").value(TENANT_UUID.toString()))
        .andExpect(jsonPath("$.name").value("Acme"))
        .andExpect(jsonPath("$.createdAt").value(containsString("2026-01-15")))
        .andExpect(jsonPath("$.deactivatedAt").doesNotExist());
  }

  @Test
  @DisplayName("POST /admin/tenants returns 400 + invalid-name when service rejects name")
  void shouldReturn400WhenNameIsBlank() throws Exception {
    Mockito.when(service.create("Acme"))
        .thenReturn(Result.failure(new TenantError.InvalidName("name must not be blank")));

    mvc.perform(
            post("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Acme"}
                    """)
                .with(withPlatformAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/tenant-invalid-name")))
        .andExpect(jsonPath("$.detail").value(containsString("blank")));
  }

  @Test
  @DisplayName("POST /admin/tenants returns 400 when Bean Validation rejects the request")
  void shouldReturn400WhenBeanValidationRejectsBlankName() throws Exception {
    mvc.perform(
            post("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":""}
                    """)
                .with(withPlatformAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("GET /admin/tenants returns 200 with list")
  void shouldReturn200WithListWhenGetAll() throws Exception {
    Tenant other =
        new Tenant(
            new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000002")),
            "Beta",
            CREATED_AT,
            Optional.empty());
    Mockito.when(service.findAll()).thenReturn(List.of(anActiveTenant(), other));

    mvc.perform(get("/admin/tenants").with(withPlatformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].name").value("Acme"))
        .andExpect(jsonPath("$[1].name").value("Beta"));
  }

  @Test
  @DisplayName("GET /admin/tenants/{id} returns 200 when found")
  void shouldReturn200WhenGetById() throws Exception {
    Mockito.when(service.findById(TENANT_ID)).thenReturn(Optional.of(anActiveTenant()));

    mvc.perform(get("/admin/tenants/" + TENANT_UUID).with(withPlatformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TENANT_UUID.toString()))
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  @DisplayName("GET /admin/tenants/{id} returns 404 + not-found when missing")
  void shouldReturn404WhenGetByIdMissing() throws Exception {
    Mockito.when(service.findById(TENANT_ID)).thenReturn(Optional.empty());

    mvc.perform(get("/admin/tenants/" + TENANT_UUID).with(withPlatformAdmin()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/tenant-not-found")));
  }

  @Test
  @DisplayName("GET /admin/tenants/{id} returns 404 when path variable is not a UUID")
  void shouldReturn404WhenGetByIdWithMalformedUuid() throws Exception {
    mvc.perform(get("/admin/tenants/not-a-uuid").with(withPlatformAdmin()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/tenant-not-found")));
  }

  @Test
  @DisplayName("DELETE /admin/tenants/{id} returns 200 when soft-delete succeeds")
  void shouldReturn200WhenDeleteSucceeds() throws Exception {
    Tenant deactivated =
        new Tenant(
            TENANT_ID, "Acme", CREATED_AT, Optional.of(Instant.parse("2026-01-16T09:00:00Z")));
    Mockito.when(service.deactivate(TENANT_ID)).thenReturn(Result.success(deactivated));

    mvc.perform(delete("/admin/tenants/" + TENANT_UUID).with(withPlatformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TENANT_UUID.toString()))
        .andExpect(jsonPath("$.deactivatedAt").value(containsString("2026-01-16")));
  }

  @Test
  @DisplayName("DELETE /admin/tenants/{id} returns 404 when tenant missing")
  void shouldReturn404WhenDeleteMissing() throws Exception {
    Mockito.when(service.deactivate(TENANT_ID))
        .thenReturn(Result.failure(new TenantError.NotFound(TENANT_ID)));

    mvc.perform(delete("/admin/tenants/" + TENANT_UUID).with(withPlatformAdmin()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/admin/tenant-not-found")));
  }

  @Test
  @DisplayName("DELETE /admin/tenants/{id} returns 200 when already deactivated (idempotent)")
  void shouldReturn200WhenDeleteIsIdempotent() throws Exception {
    Tenant deactivated =
        new Tenant(
            TENANT_ID, "Acme", CREATED_AT, Optional.of(Instant.parse("2026-01-16T09:00:00Z")));
    Mockito.when(service.deactivate(TENANT_ID))
        .thenReturn(Result.failure(new TenantError.Deactivated(TENANT_ID)));
    Mockito.when(service.findById(TENANT_ID)).thenReturn(Optional.of(deactivated));

    mvc.perform(delete("/admin/tenants/" + TENANT_UUID).with(withPlatformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TENANT_UUID.toString()))
        .andExpect(jsonPath("$.deactivatedAt").value(containsString("2026-01-16")));
  }

  @Test
  @DisplayName("POST /admin/tenants returns 403 when caller has only tenant ADMIN role")
  void shouldReturn403WhenPostAndNotPlatformAdmin() throws Exception {
    mvc.perform(
            post("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Acme"}
                    """)
                .with(withRoleOnly(Role.ADMIN)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("DELETE /admin/tenants/{id} returns 403 when caller has only tenant ADMIN role")
  void shouldReturn403WhenDeleteAndNotPlatformAdmin() throws Exception {
    mvc.perform(delete("/admin/tenants/" + TENANT_UUID).with(withRoleOnly(Role.ADMIN)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("POST /admin/tenants returns 401 when there is no Authorization header")
  void shouldReturn401WhenNoAuthHeader() throws Exception {
    mvc.perform(
            post("/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Acme"}
                    """))
        .andExpect(status().isUnauthorized());
  }
}
