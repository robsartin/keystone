package co.embracejoy.accounting.keystone.application.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    AtomicReference<LocalDate> capturedAsOf = new AtomicReference<>();
    AtomicBoolean capturedIncludeZero = new AtomicBoolean();
    TrialBalanceReadModel spy =
        (asOf, includeZero) -> {
          capturedAsOf.set(asOf);
          capturedIncludeZero.set(includeZero);
          return List.of();
        };
    TrialBalanceService service = new TrialBalanceService(spy);

    service.query(ASOF, true);

    assertThat(capturedAsOf.get()).isEqualTo(ASOF);
    assertThat(capturedIncludeZero.get()).isTrue();
  }

  @Test
  @DisplayName("rejects null readModel in constructor")
  void shouldThrowWhenReadModelIsNull() {
    assertThrows(NullPointerException.class, () -> new TrialBalanceService(null));
  }

  @Test
  @DisplayName("rejects null asOf")
  void shouldThrowWhenAsOfIsNull() {
    TrialBalanceService service = new TrialBalanceService((asOf, includeZero) -> List.of());
    assertThrows(NullPointerException.class, () -> service.query(null, false));
  }
}
