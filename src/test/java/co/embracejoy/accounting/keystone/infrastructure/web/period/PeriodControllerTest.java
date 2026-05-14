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
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PeriodController.class)
@DisplayName("PeriodController")
class PeriodControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean PeriodService service;
  @MockitoBean TenantContext tenantContext;

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));
  private static final YearMonth JUN_2026 = YearMonth.of(2026, 6);
  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

  @BeforeEach
  void stubTenant() {
    Mockito.when(tenantContext.require()).thenReturn(TENANT);
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

    mvc.perform(get("/periods").param("status", "closed"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].yearMonth").value("2026-06"))
        .andExpect(jsonPath("$[0].status").value("CLOSED"));
  }

  @Test
  @DisplayName("GET /periods/{yyyy-mm} returns 200 for a CLOSED period")
  void shouldReturnClosedPeriodByYearMonth() throws Exception {
    Mockito.when(service.findByYearMonth(TENANT, JUN_2026)).thenReturn(closedPeriod(JUN_2026));

    mvc.perform(get("/periods/2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-06"))
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("GET /periods/{yyyy-mm} returns 200 with synthesized OPEN when no row")
  void shouldReturnSynthesizedOpenPeriod() throws Exception {
    Mockito.when(service.findByYearMonth(TENANT, MAY_2026)).thenReturn(openPeriod(MAY_2026));

    mvc.perform(get("/periods/2026-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-05"))
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 200 on success")
  void shouldReturn200WhenCloseSucceeds() throws Exception {
    Mockito.when(service.close(TENANT, JUN_2026, "system"))
        .thenReturn(Result.success(closedPeriod(JUN_2026)));

    mvc.perform(post("/periods/2026-06/close"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 200 on idempotent re-close")
  void shouldReturn200WhenReCloseIsIdempotent() throws Exception {
    Mockito.when(service.close(TENANT, MAY_2026, "system"))
        .thenReturn(Result.success(closedPeriod(MAY_2026)));

    mvc.perform(post("/periods/2026-05/close"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value("2026-05"))
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  @DisplayName("POST /periods/{yyyy-mm}/close returns 400 when not sequentially closable")
  void shouldReturn400WhenNotSequentiallyClosable() throws Exception {
    Mockito.when(service.close(TENANT, JUN_2026, "system"))
        .thenReturn(Result.failure(new PeriodError.NotSequentiallyClosable(JUN_2026, MAY_2026)));

    mvc.perform(post("/periods/2026-06/close"))
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

    mvc.perform(post("/periods/2026-06/reopen"))
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

    mvc.perform(post("/periods/2026-05/reopen"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/period/not-most-recently-closed")))
        .andExpect(jsonPath("$.detail", containsString("2026-06")));
  }
}
