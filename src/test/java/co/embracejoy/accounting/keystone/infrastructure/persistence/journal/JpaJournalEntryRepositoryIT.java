package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");

  private static JournalEntry validEntry() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            LocalDate.parse("2026-05-10"),
            "opening",
            List.of(
                new Posting(CASH, Side.DEBIT, new Money(10000L, USD)),
                new Posting(EQUITY, Side.CREDIT, new Money(10000L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }

  private JournalEntry entryOn(LocalDate d) {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            d,
            "test",
            List.of(
                new Posting(CASH, Side.DEBIT, new Money(1L, USD)),
                new Posting(EQUITY, Side.CREDIT, new Money(1L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }

  @Test
  @DisplayName("save() persists the entry and returns it with a fresh JournalEntryId")
  void shouldPersistAndReturnPersistedEntryWhenSaving() {
    PersistedJournalEntry persisted = repository.save(validEntry());

    assertThat(persisted.id()).isNotNull();
    assertThat(persisted.id().value()).isNotNull();
    assertThat(persisted.entry().postings()).hasSize(2);
  }

  @Test
  @DisplayName("findById() returns Optional.empty for unknown id")
  void shouldReturnEmptyWhenIdUnknown() {
    Optional<PersistedJournalEntry> found =
        repository.findById(new JournalEntryId(java.util.UUID.randomUUID()));

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("findById() returns the entry that was saved")
  void shouldRoundTripWhenSavingAndReadingBack() {
    PersistedJournalEntry saved = repository.save(validEntry());

    Optional<PersistedJournalEntry> found = repository.findById(saved.id());

    assertThat(found).isPresent();
    PersistedJournalEntry hydrated = found.get();
    assertThat(hydrated.id()).isEqualTo(saved.id());
    assertThat(hydrated.entry().description()).isEqualTo("opening");
    assertThat(hydrated.entry().currency()).isEqualTo(USD);
    assertThat(hydrated.entry().postings()).hasSize(2);
  }

  @Test
  @DisplayName("UUID v7 ids on saved entries have version 7")
  void shouldUseVersion7UuidWhenSaving() {
    PersistedJournalEntry persisted = repository.save(validEntry());
    assertThat(persisted.id().value().version()).isEqualTo(7);
  }

  @Test
  @DisplayName("distinctOccurredMonths returns YearMonths of all persisted entries")
  void shouldReturnDistinctMonthsForPostings() {
    // Persist three entries: 2026-05, 2026-05, 2026-06.
    repository.save(entryOn(LocalDate.of(2026, 5, 1)));
    repository.save(entryOn(LocalDate.of(2026, 5, 28)));
    repository.save(entryOn(LocalDate.of(2026, 6, 15)));

    Set<YearMonth> months = repository.distinctOccurredMonths();
    assertThat(months).contains(YearMonth.of(2026, 5), YearMonth.of(2026, 6));
  }
}
