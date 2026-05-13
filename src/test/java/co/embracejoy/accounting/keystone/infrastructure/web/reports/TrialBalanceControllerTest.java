package co.embracejoy.accounting.keystone.infrastructure.web.reports;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrialBalanceController.class)
@DisplayName("TrialBalanceController")
class TrialBalanceControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean TrialBalanceService service;
  @MockitoBean TenantContext tenantContext;

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  @DisplayName("GET /reports/trial-balance returns 200 with rows from the service")
  void shouldReturn200WithRows() throws Exception {
    TrialBalanceRow cash =
        new TrialBalanceRow(new AccountCode("1000"), USD, 10000L, 0L, 10000L, 0L);
    TrialBalanceRow rev = new TrialBalanceRow(new AccountCode("4000"), USD, 0L, 10000L, 0L, 10000L);
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of(cash, rev));

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13"))
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
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance")).andExpect(status().isOk());

    // The controller calls LocalDate.now(ZoneOffset.UTC); the test does the same in the
    // same JVM. The values match exactly unless a UTC day boundary crosses between the two
    // reads, so accept "today" or "yesterday" (no future tolerance possible).
    ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
    Mockito.verify(service).query(captor.capture(), Mockito.eq(false));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    org.assertj.core.api.Assertions.assertThat(captor.getValue()).isIn(today.minusDays(1), today);
  }

  @Test
  @DisplayName("passes includeZero=true through to the service")
  void shouldPassIncludeZeroThrough() throws Exception {
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13&includeZero=true"))
        .andExpect(status().isOk());

    Mockito.verify(service).query(LocalDate.parse("2026-05-13"), true);
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when asOf is malformed")
  void shouldReturn400WhenAsOfMalformed() throws Exception {
    mvc.perform(get("/reports/trial-balance?asOf=not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("returns 200 with empty array when service returns no rows")
  void shouldReturn200WithEmptyArrayWhenNoRows() throws Exception {
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }
}
