package co.embracejoy.accounting.keystone.domain.period;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * A calendar-month period and its lifecycle status. Most months never have a {@code Period} row
 * persisted; they're implicitly {@link PeriodStatus#OPEN}. A row exists only when the status has
 * been changed at least once.
 */
public record Period(
    YearMonth yearMonth,
    PeriodStatus status,
    Optional<Instant> closedAt,
    Optional<String> closedBy,
    Optional<Instant> reopenedAt,
    Optional<String> reopenedBy) {

  public Period {
    Objects.requireNonNull(yearMonth, "yearMonth");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(closedAt, "closedAt");
    Objects.requireNonNull(closedBy, "closedBy");
    Objects.requireNonNull(reopenedAt, "reopenedAt");
    Objects.requireNonNull(reopenedBy, "reopenedBy");
    if (status == PeriodStatus.CLOSED && (closedAt.isEmpty() || closedBy.isEmpty())) {
      throw new IllegalArgumentException("CLOSED period must have closedAt and closedBy");
    }
  }

  /** Factory for the synthesized "no row exists" OPEN state. */
  public static Period openFor(YearMonth yearMonth) {
    return new Period(
        yearMonth,
        PeriodStatus.OPEN,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
