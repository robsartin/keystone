package co.embracejoy.accounting.keystone.infrastructure.web.period;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(PeriodController.class)
@Import({TestSecurityConfig.class, SecurityConfig.class})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("PeriodController")
class PeriodControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean PeriodService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  private static final TenantId TENANT = Tenants.DEFAULT_TENANT_ID;
  private static final YearMonth JUN_2026 = YearMonth.of(2026, 6);
  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

  @BeforeEach
  void setupAuth() {
    Mockito.when(tenants.findById(TENANT))
        .thenReturn(
            Optional.of(new Tenant(TENANT, "Test Tenant", Instant.now(), Optional.empty())));
    Mockito.when(platformAdmins.exists(Mockito.anyString())).thenReturn(false);
    Mockito.when(roles.findRole(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
  }

  private RequestPostProcessor withTestAuth() {
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mint("auth0|test-user", TENANT));
      return req;
    };
  }

  private static Period closedPeriod(YearMonth ym) {
    return new Period(
        TENANT,
        ym,
        PeriodStatus.CLOSED,
        Optional.of(Instant.parse("2026-07-01T00:00:00Z")),
        Optional.of("system"),
        Optional.empty(),
        Optional.empty());
  }

  private static Period openPeriod(YearMonth ym) {
    return Period.openFor(TENANT, ym);
  }

  @Test
  @DisplayName("GET /periods?status=closed returns list of closed periods")
  void shouldReturnClosedPeriodsList() throws Exception {
    Mockito.when(service.findAllClosed(TENANT)).thenReturn(List.of(closedPeriod(JUN_2026)));

    mvc.perform(get("/periods").param("status", "closed").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].yearMonth").value("2026-06"))
        .andExpect(jsonPath("$[0].status").value("CLOSED"));
  }

  @Test
  @DisplayName("GET /periods/{yyyy-mm} returns 200 for a CLOSED period")
  void shouldReturnClosedPeriodByYearMonth() throws Exception {
    Mockito.when(service.findByYearMonth(TENANT, JUN_2026)).thenReturn(closedPeriod(JUN_2026));

    mvc.perform(get("/periods/2026-06").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-06"))
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("GET /periods/{yyyy-mm} returns 200 with synthesized OPEN when no row")
  void shouldReturnSynthesizedOpenPeriod() throws Exception {
    Mockito.when(service.findByYearMonth(TENANT, MAY_2026)).thenReturn(openPeriod(MAY_2026));

    mvc.perform(get("/periods/2026-05").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-05"))
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 200 on success")
  void shouldReturn200WhenCloseSucceeds() throws Exception {
    Mockito.when(service.close(TENANT, JUN_2026, "system"))
        .thenReturn(Result.success(closedPeriod(JUN_2026)));

    mvc.perform(post("/periods/2026-06/close").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 200 on idempotent re-close")
  void shouldReturn200WhenReCloseIsIdempotent() throws Exception {
    Mockito.when(service.close(TENANT, MAY_2026, "system"))
        .thenReturn(Result.success(closedPeriod(MAY_2026)));

    mvc.perform(post("/periods/2026-05/close").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-05"))
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 400 when not sequentially closable")
  void shouldReturn400WhenNotSequentiallyClosable() throws Exception {
    Mockito.when(service.close(TENANT, JUN_2026, "system"))
        .thenReturn(Result.failure(new PeriodError.NotSequentiallyClosable(JUN_2026, MAY_2026)));

    mvc.perform(post("/periods/2026-06/close").with(withTestAuth()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/period/not-sequentially-closable")))
        .andExpect(jsonPath("$.detail", containsString("2026-05")));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/reopen returns 200 on success")
  void shouldReturn200WhenReopenSucceeds() throws Exception {
    Period reopened =
        new Period(
            TENANT,
            JUN_2026,
            PeriodStatus.OPEN,
            Optional.of(Instant.parse("2026-07-01T00:00:00Z")),
            Optional.of("system"),
            Optional.of(Instant.parse("2026-07-02T00:00:00Z")),
            Optional.of("system"));
    Mockito.when(service.reopen(TENANT, JUN_2026, "system")).thenReturn(Result.success(reopened));

    mvc.perform(post("/periods/2026-06/reopen").with(withTestAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.reopenedAt").value(containsString("2026-07-02")));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/reopen returns 400 when not most-recently-closed")
  void shouldReturn400WhenNotMostRecentlyClosed() throws Exception {
    Mockito.when(service.reopen(TENANT, MAY_2026, "system"))
        .thenReturn(
            Result.failure(new PeriodError.NotMostRecentlyClosed(MAY_2026, Optional.of(JUN_2026))));

    mvc.perform(post("/periods/2026-05/reopen").with(withTestAuth()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/period/not-most-recently-closed")))
        .andExpect(jsonPath("$.detail", containsString("2026-06")));
  }
}
