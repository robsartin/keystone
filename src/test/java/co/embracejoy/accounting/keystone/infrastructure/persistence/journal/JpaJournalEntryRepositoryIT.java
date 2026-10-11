package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.persistence.account.AccountRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = co.embracejoy.accounting.keystone.KeystoneApplication.class)
@Testcontainers
@DisplayName("JpaJournalEntryRepository (integration)")
class JpaJournalEntryRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired JpaJournalEntryRepository repository;
  @Autowired JournalEntryJpaRepository jpaRepository;
  @Autowired AccountRepositoryAdapter accountRepository;
  @Autowired TenantContext tenantContext;
  @Autowired JdbcTemplate jdbc;

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final AccountCode CASH_EUR = new AccountCode("1000-EUR");
  private static final TenantId TENANT = Tenants.DEFAULT_TENANT_ID;
  private static final String ACTOR = "auth0|test-actor";

  @BeforeEach
  void setupTenant() {
    tenantContext.set(Tenants.DEFAULT_TENANT_ID);
  }

  private static JournalEntry validEntry() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            LocalDate.parse("2026-05-10"),
            "opening",
            List.of(
                new Posting(CASH, Side.DEBIT, new Money(10000L, USD), new Money(10000L, USD)),
                new Posting(EQUITY, Side.CREDIT, new Money(10000L, USD), new Money(10000L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }

  private JournalEntry entryOn(LocalDate d) {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            d,
            "test",
            List.of(
                new Posting(CASH, Side.DEBIT, new Money(1L, USD), new Money(1L, USD)),
                new Posting(EQUITY, Side.CREDIT, new Money(1L, USD), new Money(1L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }

  @Test
  @DisplayName("save() persists the entry and returns it with a fresh JournalEntryId")
  void shouldPersistAndReturnPersistedEntryWhenSaving() {
    PersistedJournalEntry persisted = repository.save(validEntry(), ACTOR);

    assertThat(persisted.id()).isNotNull();
    assertThat(persisted.id().value()).isNotNull();
    assertThat(persisted.entry().postings()).hasSize(2);
  }

  @Test
  @DisplayName("findById() returns Optional.empty for unknown id")
  void shouldReturnEmptyWhenIdUnknown() {
    Optional<PersistedJournalEntry> found =
        repository.findById(TENANT, new JournalEntryId(java.util.UUID.randomUUID()));

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("findById() returns the entry that was saved")
  void shouldRoundTripWhenSavingAndReadingBack() {
    PersistedJournalEntry saved = repository.save(validEntry(), ACTOR);

    Optional<PersistedJournalEntry> found = repository.findById(TENANT, saved.id());

    assertThat(found).isPresent();
    PersistedJournalEntry hydrated = found.get();
    assertThat(hydrated.id()).isEqualTo(saved.id());
    assertThat(hydrated.entry().description()).isEqualTo("opening");
    assertThat(hydrated.entry().postings()).hasSize(2);
  }

  @Test
  @DisplayName("UUID v7 ids on saved entries have version 7")
  void shouldUseVersion7UuidWhenSaving() {
    PersistedJournalEntry persisted = repository.save(validEntry(), ACTOR);
    assertThat(persisted.id().value().version()).isEqualTo(7);
  }

  @Test
  @DisplayName("distinctOccurredMonths returns YearMonths of all persisted entries")
  void shouldReturnDistinctMonthsForPostings() {
    // Persist three entries: 2026-05, 2026-05, 2026-06.
    repository.save(entryOn(LocalDate.of(2026, 5, 1)), ACTOR);
    repository.save(entryOn(LocalDate.of(2026, 5, 28)), ACTOR);
    repository.save(entryOn(LocalDate.of(2026, 6, 15)), ACTOR);

    Set<YearMonth> months = repository.distinctOccurredMonths(TENANT);
    assertThat(months).contains(YearMonth.of(2026, 5), YearMonth.of(2026, 6));
  }

  @Test
  @DisplayName(
      "save+findById round-trips a multi-currency entry preserving currency and baseAmount")
  void shouldRoundTripMultiCurrencyEntry() {
    // Create an EUR cash account.
    accountRepository.save(
        new Account(
            TENANT,
            CASH_EUR,
            "Cash EUR",
            AccountType.ASSET,
            EUR,
            Optional.empty(),
            AccountStatus.ACTIVE));

    // USD→EUR entry: debit 9200 EUR (≡ $100 USD), credit 10000 USD ($100 USD).
    Posting debit =
        new Posting(CASH_EUR, Side.DEBIT, new Money(9200L, EUR), new Money(10000L, USD));
    Posting credit = new Posting(CASH, Side.CREDIT, new Money(10000L, USD), new Money(10000L, USD));

    JournalEntry entry =
        new JournalEntry(
            TENANT, LocalDate.of(2026, 5, 13), "USD→EUR transfer", List.of(debit, credit));

    PersistedJournalEntry saved = repository.save(entry, ACTOR);
    PersistedJournalEntry found = repository.findById(TENANT, saved.id()).orElseThrow();

    assertThat(found.entry().postings()).hasSize(2);
    Posting debitOut =
        found.entry().postings().stream()
            .filter(p -> p.side() == Side.DEBIT)
            .findFirst()
            .orElseThrow();
    assertThat(debitOut.amount().currency()).isEqualTo(EUR);
    assertThat(debitOut.amount().minorUnits()).isEqualTo(9200L);
    assertThat(debitOut.baseAmount().currency()).isEqualTo(USD);
    assertThat(debitOut.baseAmount().minorUnits()).isEqualTo(10000L);
  }

  @Test
  @DisplayName("save() stamps posted_by with the given actor")
  void shouldStampPostedByWithActorWhenSaving() {
    PersistedJournalEntry persisted = repository.save(validEntry(), ACTOR);

    JournalEntryEntity entity = jpaRepository.findById(persisted.id().value()).orElseThrow();
    assertThat(entity.getPostedBy()).isEqualTo(ACTOR);
  }

  @Test
  @DisplayName("saves and reads back reverses_id + reversal_reason round-trip")
  void shouldRoundTripReversalMetadata() {
    PersistedJournalEntry original = repository.save(validEntry(), ACTOR);

    JournalEntry reversalEntry =
        JournalEntry.reverse(
            original.id(), "posted to wrong account", LocalDate.now(), original.entry());
    ReversalMetadata metadata = new ReversalMetadata(original.id(), "posted to wrong account");

    PersistedJournalEntry persistedReversal =
        ((Result.Success<PersistedJournalEntry, JournalError>)
                repository.saveReversal(reversalEntry, metadata, ACTOR))
            .value();

    PersistedJournalEntry loaded =
        repository.findById(TENANT, persistedReversal.id()).orElseThrow();

    assertThat(loaded.reverses()).isPresent();
    assertThat(loaded.reverses().get().reversesId()).isEqualTo(original.id());
    assertThat(loaded.reverses().get().reason()).isEqualTo("posted to wrong account");

    JournalEntryEntity reversalEntity =
        jpaRepository.findById(persistedReversal.id().value()).orElseThrow();
    assertThat(reversalEntity.getPostedBy()).isEqualTo(ACTOR);
  }

  @Test
  @DisplayName("existsReversalOf returns false when the entry has not been reversed")
  void shouldReturnFalseWhenEntryNotReversed() {
    PersistedJournalEntry original = repository.save(validEntry(), ACTOR);

    assertThat(repository.existsReversalOf(TENANT, original.id())).isFalse();
  }

  @Test
  @DisplayName("existsReversalOf returns true once a reversal has been saved")
  void shouldReturnTrueWhenEntryHasBeenReversed() {
    PersistedJournalEntry original = repository.save(validEntry(), ACTOR);
    JournalEntry reversalEntry =
        JournalEntry.reverse(
            original.id(), "posted to wrong account", LocalDate.now(), original.entry());
    ReversalMetadata metadata = new ReversalMetadata(original.id(), "posted to wrong account");

    repository.saveReversal(reversalEntry, metadata, ACTOR);

    assertThat(repository.existsReversalOf(TENANT, original.id())).isTrue();
  }

  /**
   * Runs {@code task} on two threads released together by a barrier, so both are inside the call
   * before either can finish. Returns each outcome: the task's value, or the Throwable it threw.
   */
  private List<Object> raceTwo(Callable<Object> task) throws Exception {
    CyclicBarrier bothReady = new CyclicBarrier(2);
    Callable<Object> gated =
        () -> {
          RequestContextHolder.setRequestAttributes(
              new ServletRequestAttributes(new MockHttpServletRequest()));
          try {
            tenantContext.set(TENANT);
            bothReady.await(10, TimeUnit.SECONDS);
            return task.call();
          } catch (RuntimeException e) {
            return e;
          } finally {
            RequestContextHolder.resetRequestAttributes();
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Object> a = pool.submit(gated);
      Future<Object> b = pool.submit(gated);
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private int reversalRowsOf(PersistedJournalEntry original) {
    return jdbc.queryForObject(
        "select count(*) from journal_entries where reverses_id = ?",
        Integer.class,
        original.id().value());
  }

  @Test
  @DisplayName("exactly one reversal is persisted when two threads reverse the same original")
  void shouldPersistExactlyOneReversalWhenTwoThreadsReverseSameOriginal() throws Exception {
    PersistedJournalEntry original = repository.save(validEntry(), ACTOR);
    JournalEntry reversalEntry =
        JournalEntry.reverse(original.id(), "double click", LocalDate.now(), original.entry());
    ReversalMetadata metadata = new ReversalMetadata(original.id(), "double click");

    List<Object> outcomes = raceTwo(() -> repository.saveReversal(reversalEntry, metadata, ACTOR));

    assertThat(outcomes.stream().filter(o -> o instanceof Result.Success<?, ?>)).hasSize(1);
    assertThat(reversalRowsOf(original)).as("reversal rows for one original").isEqualTo(1);
  }
}
