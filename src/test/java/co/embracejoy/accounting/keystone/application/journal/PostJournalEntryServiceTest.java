package co.embracejoy.accounting.keystone.application.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostJournalEntryService")
class PostJournalEntryServiceTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final LocalDate TODAY = LocalDate.parse("2026-05-10");

  private FakeJournalRepo journalRepo;
  private FakeAccountRepo accountRepo;
  private FakePeriodRepo periodRepo;
  private PostJournalEntryService service;

  @BeforeEach
  void setup() {
    journalRepo = new FakeJournalRepo();
    accountRepo = new FakeAccountRepo();
    periodRepo = new FakePeriodRepo();
    // Seed the accounts used in posting tests
    accountRepo.seed(
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.ACTIVE));
    accountRepo.seed(
        new Account(
            EQUITY,
            "Owner Equity",
            AccountType.EQUITY,
            USD,
            Optional.empty(),
            AccountStatus.ACTIVE));
    PeriodService periodService = new PeriodService(periodRepo, journalRepo);
    service =
        new PostJournalEntryService(
            journalRepo, accountRepo, periodService, Currency.getInstance("USD"));
  }

  private static Posting debit(AccountCode a, long amt) {
    Money m = new Money(amt, USD);
    return new Posting(a, Side.DEBIT, m, m);
  }

  private static Posting credit(AccountCode a, long amt) {
    Money m = new Money(amt, USD);
    return new Posting(a, Side.CREDIT, m, m);
  }

  @Test
  @DisplayName("persists and returns Success when request is valid")
  void shouldPersistAndReturnSuccessWhenRequestIsValid() {
    Result<PersistedJournalEntry, JournalError> r =
        service.post(TODAY, "opening", List.of(debit(CASH, 1000L), credit(EQUITY, 1000L)));

    assertInstanceOf(Result.Success.class, r);
    assertEquals(1, journalRepo.saved.size());
    PersistedJournalEntry persisted =
        ((Result.Success<PersistedJournalEntry, JournalError>) r).value();
    assertSame(persisted, journalRepo.saved.get(0));
  }

  @Test
  @DisplayName("returns Failure and does not persist when entry is unbalanced")
  void shouldReturnFailureAndNotPersistWhenUnbalanced() {
    Result<PersistedJournalEntry, JournalError> r =
        service.post(TODAY, "bad", List.of(debit(CASH, 1000L), credit(EQUITY, 999L)));

    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.Unbalanced.class,
        ((Result.Failure<PersistedJournalEntry, JournalError>) r).error());
    assertEquals(0, journalRepo.saved.size());
  }

  @Test
  @DisplayName("returns Failure when postings are empty")
  void shouldReturnFailureWhenPostingsEmpty() {
    Result<PersistedJournalEntry, JournalError> r = service.post(TODAY, "empty", List.of());

    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.NoPostings.class,
        ((Result.Failure<PersistedJournalEntry, JournalError>) r).error());
    assertEquals(0, journalRepo.saved.size());
  }

  @Test
  @DisplayName("returns AccountNotFound when posting references unknown account")
  void shouldReturnAccountNotFoundWhenAccountUnknown() {
    AccountCode ghost = new AccountCode("9999");
    Result<PersistedJournalEntry, JournalError> r =
        service.post(TODAY, "bad", List.of(debit(ghost, 500L), credit(EQUITY, 500L)));

    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.AccountNotFound.class,
        ((Result.Failure<PersistedJournalEntry, JournalError>) r).error());
    assertEquals(0, journalRepo.saved.size());
  }

  @Test
  @DisplayName("returns PostingInClosedPeriod when period is closed")
  void shouldReturnPostingInClosedPeriodWhenPeriodClosed() {
    // Seed a closed period for the month of TODAY
    YearMonth todayMonth = YearMonth.from(TODAY);
    periodRepo.seedClosed(todayMonth);

    Result<PersistedJournalEntry, JournalError> r =
        service.post(TODAY, "bad", List.of(debit(CASH, 100L), credit(EQUITY, 100L)));

    assertInstanceOf(Result.Failure.class, r);
    JournalError error = ((Result.Failure<PersistedJournalEntry, JournalError>) r).error();
    assertInstanceOf(JournalError.PostingInClosedPeriod.class, error);
    assertEquals(todayMonth, ((JournalError.PostingInClosedPeriod) error).period());
    assertEquals(0, journalRepo.saved.size());
  }

  // ---- fakes ----

  private static final class FakeJournalRepo implements JournalEntryRepository {
    final List<PersistedJournalEntry> saved = new ArrayList<>();

    @Override
    public PersistedJournalEntry save(JournalEntry entry) {
      PersistedJournalEntry p =
          new PersistedJournalEntry(new JournalEntryId(UUID.randomUUID()), entry);
      saved.add(p);
      return p;
    }

    @Override
    public Optional<PersistedJournalEntry> findById(JournalEntryId id) {
      return Optional.empty();
    }

    @Override
    public java.util.Set<java.time.YearMonth> distinctOccurredMonths() {
      return java.util.Set.of();
    }
  }

  private static final class FakeAccountRepo implements AccountRepository {
    private final Map<AccountCode, Account> store = new HashMap<>();
    private final Set<AccountCode> parents = new HashSet<>();

    void seed(Account account) {
      store.put(account.code(), account);
      account.parentCode().ifPresent(parents::add);
    }

    @Override
    public Result<Account, AccountError> save(Account account) {
      if (store.containsKey(account.code())) {
        return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
      }
      store.put(account.code(), account);
      account.parentCode().ifPresent(parents::add);
      return Result.success(account);
    }

    @Override
    public Result<Account, AccountError> update(Account account) {
      if (!store.containsKey(account.code())) {
        return Result.failure(new AccountError.NotFound(account.code()));
      }
      store.put(account.code(), account);
      return Result.success(account);
    }

    @Override
    public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
      if (store.containsKey(newCode)) {
        return Result.failure(new AccountError.CodeInUseByPosting(newCode));
      }
      Account a = store.remove(existing);
      if (a == null) {
        return Result.failure(new AccountError.NotFound(existing));
      }
      Account renamed =
          new Account(newCode, a.name(), a.type(), a.currency(), a.parentCode(), a.status());
      store.put(newCode, renamed);
      return Result.success(renamed);
    }

    @Override
    public Optional<Account> findByCode(AccountCode code) {
      return Optional.ofNullable(store.get(code));
    }

    @Override
    public List<Account> findAll() {
      return new ArrayList<>(store.values());
    }

    @Override
    public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
      Map<AccountCode, Account> out = new HashMap<>();
      for (AccountCode c : codes) {
        Account a = store.get(c);
        if (a != null) {
          out.put(c, a);
        }
      }
      return out;
    }

    @Override
    public boolean hasChildren(AccountCode code) {
      return parents.contains(code);
    }
  }

  private static final class FakePeriodRepo implements PeriodRepository {
    private final Map<YearMonth, Period> store = new HashMap<>();

    void seedClosed(YearMonth ym) {
      store.put(
          ym,
          new Period(
              ym,
              PeriodStatus.CLOSED,
              Optional.of(Instant.parse("2026-06-01T09:00:00Z")),
              Optional.of("system"),
              Optional.empty(),
              Optional.empty()));
    }

    @Override
    public Period save(Period period) {
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
}
