package co.embracejoy.accounting.keystone.application.reports;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrialBalanceService")
class TrialBalanceServiceTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final LocalDate ASOF = LocalDate.parse("2026-05-13");

  @Test
  @DisplayName("query() returns the read model's rows unchanged")
  void shouldDelegateToReadModelAndReturnRowsUnchanged() {
    TrialBalanceRow row = new TrialBalanceRow(new AccountCode("1000"), USD, 1000L, 0L, 1000L, 0L);
    TrialBalanceReadModel fake = (asOf, includeZero) -> List.of(row);
    TrialBalanceService service = new TrialBalanceService(fake);

    List<TrialBalanceRow> rows = service.query(ASOF, false);

    assertThat(rows).containsExactly(row);
  }

  @Test
  @DisplayName("query() passes asOf and includeZero through to the read model")
  void shouldPassArgsThrough() {
    final LocalDate[] capturedAsOf = new LocalDate[1];
    final boolean[] capturedIncludeZero = new boolean[1];
    TrialBalanceReadModel spy =
        (asOf, includeZero) -> {
          capturedAsOf[0] = asOf;
          capturedIncludeZero[0] = includeZero;
          return List.of();
        };
    TrialBalanceService service = new TrialBalanceService(spy);

    service.query(ASOF, true);

    assertThat(capturedAsOf[0]).isEqualTo(ASOF);
    assertThat(capturedIncludeZero[0]).isTrue();
  }

  @Test
  @DisplayName("rejects null readModel in constructor")
  void shouldThrowWhenReadModelIsNull() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> new TrialBalanceService(null));
  }

  @Test
  @DisplayName("rejects null asOf")
  void shouldThrowWhenAsOfIsNull() {
    TrialBalanceService service = new TrialBalanceService((asOf, iz) -> List.of());
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> service.query(null, false));
  }
}
