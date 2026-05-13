package co.embracejoy.accounting.keystone.infrastructure.persistence.reports;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.infrastructure.persistence.account.AccountRepositoryAdapter;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@DisplayName("TrialBalanceJdbcReadModel (integration)")
class TrialBalanceJdbcReadModelIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired TrialBalanceJdbcReadModel readModel;
  @Autowired AccountRepositoryAdapter accountRepo;
  @Autowired JournalEntryRepository journalRepo;
  @Autowired JdbcClient jdbc;

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency EUR = Currency.getInstance("EUR");

  // Use codes outside the V4 seed range (1000, 1100, 3000, 4000) to avoid conflicts.
  private static final AccountCode CASH_USD = new AccountCode("8000");
  private static final AccountCode CASH_EUR = new AccountCode("8000-EUR");
  private static final AccountCode REVENUE = new AccountCode("8900");

  @BeforeEach
  void cleanDatabase() {
    // postings cascade-delete with journal_entries (FK ON DELETE CASCADE).
    jdbc.sql("DELETE FROM journal_entries").update();
    jdbc.sql("DELETE FROM accounts WHERE code IN (?, ?, ?)")
        .param(CASH_USD.value())
        .param(CASH_EUR.value())
        .param(REVENUE.value())
        .update();
  }

  @Test
  @DisplayName("fetch() with includeZero=false returns non-zero rows only, in code+currency order")
  void shouldReturnNonZeroRowsInOrder() {
    seedAccounts();
    seedEntry(LocalDate.parse("2026-05-10"), 10000L, USD, CASH_USD, REVENUE);

    List<TrialBalanceRow> rows = readModel.fetch(LocalDate.parse("2026-05-13"), false);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).accountCode()).isEqualTo(CASH_USD);
    assertThat(rows.get(0).currency()).isEqualTo(USD);
    assertThat(rows.get(0).debits()).isEqualTo(10000L);
    assertThat(rows.get(0).credits()).isEqualTo(0L);
    assertThat(rows.get(0).baseDebits()).isEqualTo(10000L);
    assertThat(rows.get(0).baseCredits()).isEqualTo(0L);
    assertThat(rows.get(0).balance()).isEqualTo(10000L);
    assertThat(rows.get(1).accountCode()).isEqualTo(REVENUE);
    assertThat(rows.get(1).credits()).isEqualTo(10000L);
    assertThat(rows.get(1).balance()).isEqualTo(-10000L);
  }

  @Test
  @DisplayName("fetch() respects the asOf filter: postings after asOf are excluded")
  void shouldExcludePostingsAfterAsOf() {
    seedAccounts();
    seedEntry(LocalDate.parse("2026-05-10"), 5000L, USD, CASH_USD, REVENUE);
    seedEntry(LocalDate.parse("2026-05-20"), 3000L, USD, CASH_USD, REVENUE);

    List<TrialBalanceRow> rows = readModel.fetch(LocalDate.parse("2026-05-15"), false);

    TrialBalanceRow cash =
        rows.stream().filter(r -> r.accountCode().equals(CASH_USD)).findFirst().orElseThrow();
    assertThat(cash.debits()).isEqualTo(5000L);
    assertThat(cash.balance()).isEqualTo(5000L);
  }

  @Test
  @DisplayName("fetch() returns one row per (accountCode, currency) — multi-currency split")
  void shouldGroupByAccountCodeAndCurrency() {
    seedAccounts();
    accountRepo.save(
        new Account(
            CASH_EUR, "Cash EUR", AccountType.ASSET, EUR, Optional.empty(), AccountStatus.ACTIVE));

    // USD→EUR transfer: debit 9200 EUR / credit 10000 USD; baseAmount=USD on both.
    Posting debitEur =
        new Posting(CASH_EUR, Side.DEBIT, new Money(9200L, EUR), new Money(10000L, USD));
    Posting creditUsd =
        new Posting(CASH_USD, Side.CREDIT, new Money(10000L, USD), new Money(10000L, USD));
    JournalEntry entry =
        new JournalEntry(LocalDate.parse("2026-05-12"), "transfer", List.of(debitEur, creditUsd));
    journalRepo.save(entry);

    List<TrialBalanceRow> rows = readModel.fetch(LocalDate.parse("2026-05-13"), false);

    TrialBalanceRow eurRow =
        rows.stream().filter(r -> r.currency().equals(EUR)).findFirst().orElseThrow();
    assertThat(eurRow.accountCode()).isEqualTo(CASH_EUR);
    assertThat(eurRow.debits()).isEqualTo(9200L);
    assertThat(eurRow.baseDebits()).isEqualTo(10000L);
    TrialBalanceRow usdRow =
        rows.stream()
            .filter(r -> r.accountCode().equals(CASH_USD) && r.currency().equals(USD))
            .findFirst()
            .orElseThrow();
    assertThat(usdRow.credits()).isEqualTo(10000L);
    assertThat(usdRow.baseCredits()).isEqualTo(10000L);
  }

  @Test
  @DisplayName("fetch() with includeZero=true returns rows whose balance is zero")
  void shouldIncludeZeroBalanceRowsWhenFlagIsTrue() {
    seedAccounts();
    // Post + reverse: net balance is zero per account.
    seedEntry(LocalDate.parse("2026-05-10"), 7000L, USD, CASH_USD, REVENUE);
    seedEntry(LocalDate.parse("2026-05-11"), 7000L, USD, REVENUE, CASH_USD);

    List<TrialBalanceRow> withZeros = readModel.fetch(LocalDate.parse("2026-05-13"), true);
    List<TrialBalanceRow> withoutZeros = readModel.fetch(LocalDate.parse("2026-05-13"), false);

    assertThat(withZeros).hasSize(2);
    assertThat(withZeros.stream().allMatch(r -> r.balance() == 0L)).isTrue();
    assertThat(withoutZeros).isEmpty();
  }

  @Test
  @DisplayName("fetch() returns empty list when no postings on or before asOf")
  void shouldReturnEmptyWhenNoEntries() {
    seedAccounts();
    seedEntry(LocalDate.parse("2026-05-20"), 100L, USD, CASH_USD, REVENUE);

    List<TrialBalanceRow> rows = readModel.fetch(LocalDate.parse("2026-05-01"), true);

    assertThat(rows).isEmpty();
  }

  // ---- helpers ----

  private void seedAccounts() {
    accountRepo.save(
        new Account(
            CASH_USD, "Cash USD", AccountType.ASSET, USD, Optional.empty(), AccountStatus.ACTIVE));
    accountRepo.save(
        new Account(
            REVENUE, "Revenue", AccountType.REVENUE, USD, Optional.empty(), AccountStatus.ACTIVE));
  }

  private void seedEntry(
      LocalDate occurredOn,
      long minorUnits,
      Currency currency,
      AccountCode debit,
      AccountCode credit) {
    Money m = new Money(minorUnits, currency);
    JournalEntry entry =
        new JournalEntry(
            occurredOn,
            "seed " + occurredOn,
            List.of(new Posting(debit, Side.DEBIT, m, m), new Posting(credit, Side.CREDIT, m, m)));
    journalRepo.save(entry);
  }
}
