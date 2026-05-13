package co.embracejoy.accounting.keystone.application.period;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PeriodService")
class PeriodServiceTest {

  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);
  private static final YearMonth JUNE_2026 = YearMonth.of(2026, 6);
  private static final String ACTOR = "system";

  private FakePeriodRepository periodRepo;
  private FakeJournalEntryRepository journalRepo;
  private PeriodService service;

  @BeforeEach
  void setup() {
    periodRepo = new FakePeriodRepository();
    journalRepo = new FakeJournalEntryRepository();
    service = new PeriodService(periodRepo, journalRepo);
  }

  @Test
  @DisplayName("close succeeds when no postings exist in any month")
  void shouldCloseWhenSequentiallyValid() {
    Result<Period, PeriodError> r = service.close(MAY_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Success.class);
    Period p = ((Result.Success<Period, PeriodError>) r).value();
    assertThat(p.status()).isEqualTo(PeriodStatus.CLOSED);
    assertThat(p.yearMonth()).isEqualTo(MAY_2026);
    assertThat(p.closedBy()).contains(ACTOR);
  }

  @Test
  @DisplayName("close succeeds when all earlier months with postings are already closed")
  void shouldCloseWhenAllEarlierPostingMonthsAreAlreadyClosed() {
    // May has postings and is already closed; June has postings; close June
    journalRepo.addOccurredMonth(MAY_2026);
    journalRepo.addOccurredMonth(JUNE_2026);
    Period mayClosed = closedPeriod(MAY_2026);
    periodRepo.store(mayClosed);

    Result<Period, PeriodError> r = service.close(JUNE_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(((Result.Success<Period, PeriodError>) r).value().yearMonth()).isEqualTo(JUNE_2026);
  }

  @Test
  @DisplayName("close rejects when an earlier open month has postings")
  void shouldRejectCloseWhenEarlierOpenMonthHasPostings() {
    // May is open and has postings; trying to close June
    journalRepo.addOccurredMonth(MAY_2026);
    journalRepo.addOccurredMonth(JUNE_2026);

    Result<Period, PeriodError> r = service.close(JUNE_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Failure.class);
    PeriodError error = ((Result.Failure<Period, PeriodError>) r).error();
    assertThat(error).isInstanceOf(PeriodError.NotSequentiallyClosable.class);
    PeriodError.NotSequentiallyClosable e = (PeriodError.NotSequentiallyClosable) error;
    assertThat(e.attempted()).isEqualTo(JUNE_2026);
    assertThat(e.earliestOpenActive()).isEqualTo(MAY_2026);
  }

  @Test
  @DisplayName("close is idempotent when the period is already closed")
  void shouldCloseIdempotentlyWhenAlreadyClosed() {
    Period mayClosed = closedPeriod(MAY_2026);
    periodRepo.store(mayClosed);

    Result<Period, PeriodError> r = service.close(MAY_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Success.class);
    // Should return the existing period unchanged — no second row saved
    assertThat(periodRepo.saveCount).isEqualTo(0);
  }

  @Test
  @DisplayName("reopen succeeds when target is the most-recently-closed period")
  void shouldReopenLatestClosed() {
    Period mayClosed = closedPeriod(MAY_2026);
    periodRepo.store(mayClosed);

    Result<Period, PeriodError> r = service.reopen(MAY_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Success.class);
    Period reopened = ((Result.Success<Period, PeriodError>) r).value();
    assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
    assertThat(reopened.reopenedBy()).contains(ACTOR);
    assertThat(reopened.reopenedAt()).isPresent();
    // Audit fields from close are preserved
    assertThat(reopened.closedAt()).isEqualTo(mayClosed.closedAt());
    assertThat(reopened.closedBy()).isEqualTo(mayClosed.closedBy());
  }

  @Test
  @DisplayName("reopen rejects when target is not the most-recently-closed period")
  void shouldRejectReopenWhenNotMostRecent() {
    Period mayClosed = closedPeriod(MAY_2026);
    Period juneClosed = closedPeriod(JUNE_2026);
    periodRepo.store(mayClosed);
    periodRepo.store(juneClosed);

    Result<Period, PeriodError> r = service.reopen(MAY_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Failure.class);
    PeriodError error = ((Result.Failure<Period, PeriodError>) r).error();
    assertThat(error).isInstanceOf(PeriodError.NotMostRecentlyClosed.class);
    PeriodError.NotMostRecentlyClosed e = (PeriodError.NotMostRecentlyClosed) error;
    assertThat(e.attempted()).isEqualTo(MAY_2026);
    assertThat(e.latestClosed()).contains(JUNE_2026);
  }

  @Test
  @DisplayName("reopen rejects when there are no closed periods")
  void shouldRejectReopenWhenNoClosedExist() {
    Result<Period, PeriodError> r = service.reopen(MAY_2026, ACTOR);

    assertThat(r).isInstanceOf(Result.Failure.class);
    PeriodError error = ((Result.Failure<Period, PeriodError>) r).error();
    assertThat(error).isInstanceOf(PeriodError.NotMostRecentlyClosed.class);
    PeriodError.NotMostRecentlyClosed e = (PeriodError.NotMostRecentlyClosed) error;
    assertThat(e.attempted()).isEqualTo(MAY_2026);
    assertThat(e.latestClosed()).isEmpty();
  }

  @Test
  @DisplayName("findByYearMonth synthesizes an OPEN period when no row exists")
  void shouldSynthesizeOpenWhenNoRowForFindByYearMonth() {
    Period p = service.findByYearMonth(MAY_2026);

    assertThat(p.yearMonth()).isEqualTo(MAY_2026);
    assertThat(p.status()).isEqualTo(PeriodStatus.OPEN);
    assertThat(p.closedAt()).isEmpty();
    assertThat(p.closedBy()).isEmpty();
  }

  // ---- helpers ----

  private static Period closedPeriod(YearMonth ym) {
    return new Period(
        ym,
        PeriodStatus.CLOSED,
        Optional.of(Instant.parse("2026-06-01T09:00:00Z")),
        Optional.of(ACTOR),
        Optional.empty(),
        Optional.empty());
  }

  // ---- fakes ----

  private static final class FakePeriodRepository implements PeriodRepository {
    private final Map<YearMonth, Period> store = new HashMap<>();
    int saveCount = 0;

    void store(Period p) {
      store.put(p.yearMonth(), p);
    }

    @Override
    public Period save(Period period) {
      saveCount++;
      store.put(period.yearMonth(), period);
      return period;
    }

    @Override
    public Period update(Period period) {
      store.put(period.yearMonth(), period);
      return period;
    }

    @Override
    public Optional<Period> findByYearMonth(YearMonth yearMonth) {
      return Optional.ofNullable(store.get(yearMonth));
    }

    @Override
    public List<Period> findAllClosed() {
      return store.values().stream()
          .filter(p -> p.status() == PeriodStatus.CLOSED)
          .sorted((a, b) -> b.yearMonth().compareTo(a.yearMonth()))
          .toList();
    }

    @Override
    public Optional<Period> findLatestClosed() {
      return findAllClosed().stream().findFirst();
    }
  }

  private static final class FakeJournalEntryRepository implements JournalEntryRepository {
    private final Set<YearMonth> months = new HashSet<>();

    void addOccurredMonth(YearMonth ym) {
      months.add(ym);
    }

    @Override
    public PersistedJournalEntry save(JournalEntry entry) {
      throw new UnsupportedOperationException("not needed in PeriodServiceTest");
    }

    @Override
    public Optional<PersistedJournalEntry> findById(JournalEntryId id) {
      throw new UnsupportedOperationException("not needed in PeriodServiceTest");
    }

    @Override
    public Set<YearMonth> distinctOccurredMonths() {
      return Set.copyOf(months);
    }
  }
}
