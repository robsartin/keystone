package co.embracejoy.accounting.keystone.domain.period;

import java.time.YearMonth;
import java.util.Optional;

/** Errors raised by {@code PeriodService} operations. */
public sealed interface PeriodError {

  /**
   * The requested close would skip earlier periods that still have unclosed postings.
   *
   * @param attempted the YearMonth the caller tried to close
   * @param earliestOpenActive the earliest YearMonth with at least one posting that is still not
   *     closed; the caller should close this one (or one before it) first
   */
  record NotSequentiallyClosable(YearMonth attempted, YearMonth earliestOpenActive)
      implements PeriodError {}

  /**
   * Only the most-recently-closed period can be reopened. The caller tried to reopen a different
   * one (or there are no closed periods at all).
   */
  record NotMostRecentlyClosed(YearMonth attempted, Optional<YearMonth> latestClosed)
      implements PeriodError {}

  /**
   * No persisted Period row for the requested YearMonth (used only by paths that don't synthesize).
   */
  record NotFound(YearMonth yearMonth) implements PeriodError {}
}
