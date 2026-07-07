package co.embracejoy.accounting.keystone.infrastructure.web.reports;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TrialBalanceController.class)
@Import({TestSecurityConfig.class, SecurityConfig.class})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("TrialBalanceController")
class TrialBalanceControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean TrialBalanceService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TEST_TENANT = Tenants.DEFAULT_TENANT_ID;

  @BeforeEach
  void setupAuth() {
    Mockito.when(tenants.findById(TEST_TENANT))
        .thenReturn(
            Optional.of(new Tenant(TEST_TENANT, "Test Tenant", Instant.now(), Optional.empty())));
    Mockito.when(platformAdmins.exists(Mockito.anyString())).thenReturn(false);
    Mockito.when(roles.findRole(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
  }

  /**
   * Mints a JWT with a valid tenant claim but no in-tenant role stubbed (see {@code setupAuth}).
   */
  private RequestPostProcessor withTestAuth() {
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mint("auth0|test-user", TEST_TENANT));
      return req;
    };
  }

  private RequestPostProcessor withTestAuth(Role role) {
    Mockito.when(roles.findRole(TEST_TENANT, "auth0|test-user"))
        .thenReturn(
            Optional.of(
                new TenantUserRole(TEST_TENANT, "auth0|test-user", role, Instant.EPOCH, "system")));
    return withTestAuth();
  }

  @Test
  @DisplayName("GET /reports/trial-balance returns 200 with rows from the service")
  void shouldReturn200WithRows() throws Exception {
    TrialBalanceRow cash =
        new TrialBalanceRow(new AccountCode("1000"), USD, 10000L, 0L, 10000L, 0L);
    TrialBalanceRow rev = new TrialBalanceRow(new AccountCode("4000"), USD, 0L, 10000L, 0L, 10000L);
    Mockito.when(
            service.query(
                Mockito.eq(TEST_TENANT), Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of(cash, rev));

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].accountCode").value("1000"))
        .andExpect(jsonPath("$[0].currency").value("USD"))
        .andExpect(jsonPath("$[0].debits").value(10000))
        .andExpect(jsonPath("$[0].credits").value(0))
        .andExpect(jsonPath("$[0].balance").value(10000))
        .andExpect(jsonPath("$[0].baseDebits").value(10000))
        .andExpect(jsonPath("$[0].baseCredits").value(0))
        .andExpect(jsonPath("$[0].baseBalance").value(10000))
        .andExpect(jsonPath("$[1].accountCode").value("4000"))
        .andExpect(jsonPath("$[1].balance").value(-10000));
  }

  @Test
  @DisplayName("defaults asOf to today (UTC) when no query param given")
  void shouldDefaultAsOfToTodayWhenMissing() throws Exception {
    Mockito.when(
            service.query(
                Mockito.eq(TEST_TENANT), Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk());

    // The controller calls LocalDate.now(ZoneOffset.UTC); the test does the same in the
    // same JVM. The values match exactly unless a UTC day boundary crosses between the two
    // reads, so accept "today" or "yesterday" (no future tolerance possible).
    ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
    Mockito.verify(service).query(Mockito.eq(TEST_TENANT), captor.capture(), Mockito.eq(false));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    org.assertj.core.api.Assertions.assertThat(captor.getValue()).isIn(today.minusDays(1), today);
  }

  @Test
  @DisplayName("passes includeZero=true through to the service")
  void shouldPassIncludeZeroThrough() throws Exception {
    Mockito.when(
            service.query(
                Mockito.eq(TEST_TENANT), Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(
            get("/reports/trial-balance?asOf=2026-05-13&includeZero=true")
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk());

    Mockito.verify(service).query(TEST_TENANT, LocalDate.parse("2026-05-13"), true);
  }

  @Test
  @DisplayName("resolves TenantId from the JWT tenant claim and passes it to the service")
  void shouldResolveTenantFromJwtAndPassToService() throws Exception {
    Mockito.when(
            service.query(
                Mockito.eq(TEST_TENANT), Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk());

    // TENANT comes from the JWT tenant claim (see withTestAuth). JwtTenantConverter populates
    // TenantContext; TrialBalanceController pulls it and passes to the service.
    Mockito.verify(service).query(Mockito.eq(TEST_TENANT), Mockito.any(), Mockito.anyBoolean());
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when asOf is malformed")
  void shouldReturn400WhenAsOfMalformed() throws Exception {
    mvc.perform(get("/reports/trial-balance?asOf=not-a-date").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("returns 200 with empty array when service returns no rows")
  void shouldReturn200WithEmptyArrayWhenNoRows() throws Exception {
    Mockito.when(
            service.query(
                Mockito.eq(TEST_TENANT), Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  @DisplayName("returns 401 when no Authorization header is present")
  void shouldReturn401WhenNoAuthHeader() throws Exception {
    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("returns 403 when the JWT has a tenant claim but no role granted in that tenant")
  void shouldReturn403WhenNoRoleGranted() throws Exception {
    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13").with(withTestAuth()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }
}
