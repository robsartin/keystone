package co.embracejoy.accounting.keystone.domain.period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PeriodError")
class PeriodErrorTest {

  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);
  private static final YearMonth JUNE_2026 = YearMonth.of(2026, 6);

  @Test
  @DisplayName("NotSequentiallyClosable carries attempted and earliest-open-active")
  void notSequentiallyClosableCarriesBoth() {
    PeriodError e = new PeriodError.NotSequentiallyClosable(JUNE_2026, MAY_2026);
    assertInstanceOf(PeriodError.NotSequentiallyClosable.class, e);
    PeriodError.NotSequentiallyClosable n = (PeriodError.NotSequentiallyClosable) e;
    assertEquals(JUNE_2026, n.attempted());
    assertEquals(MAY_2026, n.earliestOpenActive());
  }

  @Test
  @DisplayName("NotMostRecentlyClosed carries attempted and latestClosed")
  void notMostRecentlyClosedCarriesBoth() {
    PeriodError e = new PeriodError.NotMostRecentlyClosed(MAY_2026, Optional.of(JUNE_2026));
    PeriodError.NotMostRecentlyClosed n = (PeriodError.NotMostRecentlyClosed) e;
    assertEquals(MAY_2026, n.attempted());
    assertEquals(Optional.of(JUNE_2026), n.latestClosed());
  }

  @Test
  @DisplayName("NotFound carries the queried yearMonth")
  void notFoundCarriesYearMonth() {
    PeriodError e = new PeriodError.NotFound(MAY_2026);
    assertEquals(MAY_2026, ((PeriodError.NotFound) e).yearMonth());
  }

  @Test
  @DisplayName("PeriodError is sealed and lists every variant")
  void sealedListIsComplete() {
    assertEquals(3, PeriodError.class.getPermittedSubclasses().length);
  }
}
