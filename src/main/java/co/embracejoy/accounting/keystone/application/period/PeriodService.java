package co.embracejoy.accounting.keystone.application.period;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class PeriodService {

  private final PeriodRepository periods;
  private final JournalEntryRepository journals;

  public PeriodService(PeriodRepository periods, JournalEntryRepository journals) {
    this.periods = Objects.requireNonNull(periods, "periods");
    this.journals = Objects.requireNonNull(journals, "journals");
  }

  public Result<Period, PeriodError> close(TenantId tenantId, YearMonth target, String actor) {
    Optional<Period> existing = periods.findByYearMonth(tenantId, target);
    if (existing.isPresent() && existing.get().status() == PeriodStatus.CLOSED) {
      return Result.success(existing.get()); // idempotent
    }

    // Compute the earliest open YearMonth that has at least one posting.
    Set<YearMonth> closedMonths =
        periods.findAllClosed(tenantId).stream()
            .map(Period::yearMonth)
            .collect(Collectors.toUnmodifiableSet());
    TreeSet<YearMonth> openActive = new TreeSet<>();
    for (YearMonth m : journals.distinctOccurredMonths()) {
      if (!closedMonths.contains(m)) {
        openActive.add(m);
      }
    }

    YearMonth earliestOpenActive = openActive.isEmpty() ? null : openActive.first();
    if (earliestOpenActive != null && target.isAfter(earliestOpenActive)) {
      return Result.failure(new PeriodError.NotSequentiallyClosable(target, earliestOpenActive));
    }

    Period closed =
        new Period(
            tenantId,
            target,
            PeriodStatus.CLOSED,
            Optional.of(Instant.now()),
            Optional.of(actor),
            Optional.empty(),
            Optional.empty());
    return Result.success(existing.isPresent() ? periods.update(closed) : periods.save(closed));
  }

  public Result<Period, PeriodError> reopen(TenantId tenantId, YearMonth target, String actor) {
    Optional<Period> latest = periods.findLatestClosed(tenantId);
    if (latest.isEmpty() || !latest.get().yearMonth().equals(target)) {
      return Result.failure(
          new PeriodError.NotMostRecentlyClosed(target, latest.map(Period::yearMonth)));
    }
    Period reopened =
        new Period(
            tenantId,
            target,
            PeriodStatus.OPEN,
            latest.get().closedAt(),
            latest.get().closedBy(),
            Optional.of(Instant.now()),
            Optional.of(actor));
    return Result.success(periods.update(reopened));
  }

  public Period findByYearMonth(TenantId tenantId, YearMonth target) {
    return periods
        .findByYearMonth(tenantId, target)
        .orElseGet(() -> Period.openFor(tenantId, target));
  }

  public List<Period> findAllClosed(TenantId tenantId) {
    return periods.findAllClosed(tenantId);
  }
}
