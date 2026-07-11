package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryQuery;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.ReversedByMetadata;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.persistence.account.AccountRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
@DisplayName("JournalEntryJdbcReadModel (integration)")
class JournalEntryJdbcReadModelIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired JournalEntryJdbcReadModel readModel;
  @Autowired JournalEntryRepository journalRepo;
  @Autowired AccountRepositoryAdapter accountRepo;
  @Autowired JdbcClient jdbc;
  @Autowired TenantContext tenantContext;

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode RECEIVABLE = new AccountCode("1100");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final AccountCode REVENUE = new AccountCode("4000");
  private static final TenantId DEFAULT_TENANT = Tenants.DEFAULT_TENANT_ID;
  private static final TenantId OTHER_TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000003"));

  @BeforeEach
  void cleanDatabase() {
    // journal_entries cascade-deletes postings (V1 FK ON DELETE CASCADE). Accounts (seeded by
    // V4: 1000/1100/3000/4000) and the tenants table are left alone — no test mutates them.
    jdbc.sql("DELETE FROM journal_entries").update();
    tenantContext.set(DEFAULT_TENANT);
  }

  @Test
  @DisplayName("findMany with no filters returns all entries ordered by id ASC, respecting limit")
  void shouldReturnAllEntriesOrderedByIdWhenNoFiltersApplied() {
    seedEntry("first", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    seedEntry("second", LocalDate.of(2026, 6, 2), CASH, EQUITY, 100L);
    seedEntry("third", LocalDate.of(2026, 6, 3), CASH, EQUITY, 100L);

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, query(50));

    assertThat(page.items()).hasSize(3);
    assertThat(page.nextCursor()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("findMany filters entries to those occurring within [from, to] inclusive")
  void shouldReturnOnlyEntriesWithinRangeWhenFromAndToGiven() {
    seedEntry("june-1", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    seedEntry("june-30", LocalDate.of(2026, 6, 30), CASH, EQUITY, 100L);
    seedEntry("july-1", LocalDate.of(2026, 7, 1), CASH, EQUITY, 100L);

    JournalEntryQuery q =
        new JournalEntryQuery(
            Optional.of(LocalDate.of(2026, 6, 1)),
            Optional.of(LocalDate.of(2026, 6, 30)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50);

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, q);

    assertThat(page.items())
        .extracting(p -> p.entry().description())
        .containsExactlyInAnyOrder("june-1", "june-30");
  }

  @Test
  @DisplayName("findMany filters entries to those with a posting against the given account")
  void shouldReturnOnlyMatchingEntriesWhenAccountFilterGiven() {
    seedEntry("touches-cash", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    seedEntry("touches-receivable-only", LocalDate.of(2026, 6, 2), RECEIVABLE, REVENUE, 100L);

    JournalEntryQuery q = queryWithAccount(CASH);

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, q);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).entry().description()).isEqualTo("touches-cash");
  }

  @Test
  @DisplayName("findMany filters entries whose description contains the q substring")
  void shouldReturnOnlyMatchingEntriesWhenDescriptionSubstringGiven() {
    seedEntry("coffee purchase", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    seedEntry("office supplies", LocalDate.of(2026, 6, 2), CASH, EQUITY, 100L);

    JournalEntryQuery q = queryWithQ("coffee");

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, q);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).entry().description()).isEqualTo("coffee purchase");
  }

  @Test
  @DisplayName(
      "findMany applies amountMin to the entry's aggregated total debit (HAVING SUM), not a "
          + "per-posting WHERE — an entry whose split postings are individually below the "
          + "threshold but sum at or above it must still be returned")
  void shouldFilterOnAggregatedTotalDebitWhenAmountRangeGiven() {
    seedEntry("small-single-posting", LocalDate.of(2026, 6, 1), CASH, EQUITY, 10L);
    seedEntry("large-single-posting", LocalDate.of(2026, 6, 2), CASH, EQUITY, 100L);
    // Two DEBIT postings of 25 each: total debit is 50 (>= amountMin below), but every
    // individual posting row is 25 (< amountMin). A naive `WHERE amount_minor_units >= :min`
    // would wrongly exclude this entry; HAVING SUM(...) correctly includes it.
    seedSplitDebitEntry("split-debits-sum-to-50", LocalDate.of(2026, 6, 3), 25L, 25L);

    JournalEntryQuery q = queryWithAmountMin(50L);

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, q);

    assertThat(page.items())
        .extracting(p -> p.entry().description())
        .containsExactlyInAnyOrder("large-single-posting", "split-debits-sum-to-50");
  }

  @Test
  @DisplayName(
      "findMany cursor pagination returns successive pages and an empty cursor once exhausted")
  void shouldReturnSuccessivePagesWhenCursorGiven() {
    for (int i = 1; i <= 5; i++) {
      seedEntry("entry-" + i, LocalDate.of(2026, 6, i), CASH, EQUITY, 100L);
    }

    JournalEntryPage page1 = readModel.findMany(DEFAULT_TENANT, queryWithLimit(2));
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.nextCursor()).isPresent();

    JournalEntryPage page2 =
        readModel.findMany(DEFAULT_TENANT, queryWithAfter(page1.nextCursor().orElseThrow(), 2));
    assertThat(page2.items()).hasSize(2);
    assertThat(page2.nextCursor()).isPresent();

    JournalEntryPage page3 =
        readModel.findMany(DEFAULT_TENANT, queryWithAfter(page2.nextCursor().orElseThrow(), 2));
    assertThat(page3.items()).hasSize(1);
    assertThat(page3.nextCursor()).isEqualTo(Optional.empty());

    Set<UUID> allIds = new HashSet<>();
    page1.items().forEach(p -> allIds.add(p.id().value()));
    page2.items().forEach(p -> allIds.add(p.id().value()));
    page3.items().forEach(p -> allIds.add(p.id().value()));
    assertThat(allIds).hasSize(5);
  }

  @Test
  @DisplayName("findMany isolates data by tenant — other tenant's entries are not returned")
  void shouldExcludeOtherTenantEntriesWhenListingByTenant() {
    seedEntry("default-tenant-entry", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    seedOtherTenantEntry();

    JournalEntryPage page = readModel.findMany(DEFAULT_TENANT, query(50));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).entry().description()).isEqualTo("default-tenant-entry");
  }

  @Test
  @DisplayName("findById returns empty when no entry exists for the given id")
  void shouldReturnEmptyWhenEntryNotFound() {
    Optional<PersistedJournalEntry> result =
        readModel.findById(DEFAULT_TENANT, new JournalEntryId(UUID.randomUUID()));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("findById returns an entry with empty reversal metadata when it was never reversed")
  void shouldReturnEntryWithoutReversalMetadataWhenNeverReversed() {
    PersistedJournalEntry seeded =
        seedEntry("plain-entry", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);

    Optional<PersistedJournalEntry> result = readModel.findById(DEFAULT_TENANT, seeded.id());

    assertThat(result).isPresent();
    assertThat(result.get().reverses()).isEmpty();
    assertThat(result.get().reversedBy()).isEmpty();
  }

  @Test
  @DisplayName(
      "findById surfaces reversedBy metadata (via LEFT JOIN) on the original of a reversal")
  void shouldReturnReversedByMetadataForOriginalOfAReversalPair() {
    PersistedJournalEntry original =
        seedEntry("original", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    PersistedJournalEntry reversal = seedReversalOf(original, "typo");

    Optional<PersistedJournalEntry> result = readModel.findById(DEFAULT_TENANT, original.id());

    assertThat(result).isPresent();
    assertThat(result.get().reversedBy()).isPresent();
    ReversedByMetadata metadata = result.get().reversedBy().orElseThrow();
    assertThat(metadata.reversalId()).isEqualTo(reversal.id());
    assertThat(metadata.reversedBy()).isEqualTo("reversal-actor");
    assertThat(metadata.reason()).isEqualTo("typo");
  }

  @Test
  @DisplayName("findById surfaces reverses metadata (from the row) on the reversal entry itself")
  void shouldReturnReversesMetadataForReversalEntry() {
    PersistedJournalEntry original =
        seedEntry("original", LocalDate.of(2026, 6, 1), CASH, EQUITY, 100L);
    PersistedJournalEntry reversal = seedReversalOf(original, "typo");

    Optional<PersistedJournalEntry> result = readModel.findById(DEFAULT_TENANT, reversal.id());

    assertThat(result).isPresent();
    assertThat(result.get().reverses()).isPresent();
    ReversalMetadata metadata = result.get().reverses().orElseThrow();
    assertThat(metadata.reversesId()).isEqualTo(original.id());
    assertThat(metadata.reason()).isEqualTo("typo");
  }

  // ---- helpers ----

  private JournalEntryQuery query(int limit) {
    return new JournalEntryQuery(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        limit);
  }

  private JournalEntryQuery queryWithAccount(AccountCode account) {
    return new JournalEntryQuery(
        Optional.empty(),
        Optional.empty(),
        Optional.of(account),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        50);
  }

  private JournalEntryQuery queryWithQ(String q) {
    return new JournalEntryQuery(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(q),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        50);
  }

  private JournalEntryQuery queryWithAmountMin(long amountMin) {
    return new JournalEntryQuery(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(amountMin),
        Optional.empty(),
        Optional.empty(),
        50);
  }

  private JournalEntryQuery queryWithLimit(int limit) {
    return query(limit);
  }

  private JournalEntryQuery queryWithAfter(JournalEntryId after, int limit) {
    return new JournalEntryQuery(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(after),
        limit);
  }

  private PersistedJournalEntry seedEntry(
      String description,
      LocalDate occurredOn,
      AccountCode debit,
      AccountCode credit,
      long minorUnits) {
    Money m = new Money(minorUnits, USD);
    JournalEntry entry =
        new JournalEntry(
            DEFAULT_TENANT,
            occurredOn,
            description,
            List.of(new Posting(debit, Side.DEBIT, m, m), new Posting(credit, Side.CREDIT, m, m)));
    return journalRepo.save(entry, "test-actor");
  }

  private PersistedJournalEntry seedReversalOf(PersistedJournalEntry original, String reason) {
    JournalEntry reversal =
        JournalEntry.reverse(original.id(), reason, LocalDate.now(), original.entry());
    return journalRepo.saveReversal(
        reversal, new ReversalMetadata(original.id(), reason), "reversal-actor");
  }

  private void seedSplitDebitEntry(
      String description, LocalDate occurredOn, long debit1, long debit2) {
    Money m1 = new Money(debit1, USD);
    Money m2 = new Money(debit2, USD);
    Money credit = new Money(debit1 + debit2, USD);
    JournalEntry entry =
        new JournalEntry(
            DEFAULT_TENANT,
            occurredOn,
            description,
            List.of(
                new Posting(CASH, Side.DEBIT, m1, m1),
                new Posting(RECEIVABLE, Side.DEBIT, m2, m2),
                new Posting(EQUITY, Side.CREDIT, credit, credit)));
    journalRepo.save(entry, "test-actor");
  }

  private void seedOtherTenantEntry() {
    jdbc.sql("INSERT INTO tenants (id, name) VALUES (:id, 'Other Tenant') ON CONFLICT DO NOTHING")
        .param("id", OTHER_TENANT.value())
        .update();

    tenantContext.set(OTHER_TENANT);
    seedOtherTenantAccounts();
    Money m = new Money(100L, USD);
    JournalEntry entry =
        new JournalEntry(
            OTHER_TENANT,
            LocalDate.of(2026, 6, 2),
            "other-tenant-entry",
            List.of(new Posting(CASH, Side.DEBIT, m, m), new Posting(EQUITY, Side.CREDIT, m, m)));
    journalRepo.save(entry, "test-actor");
    tenantContext.set(DEFAULT_TENANT);
  }

  private void seedOtherTenantAccounts() {
    accountRepo.save(
        new Account(
            OTHER_TENANT,
            CASH,
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.empty(),
            AccountStatus.ACTIVE));
    accountRepo.save(
        new Account(
            OTHER_TENANT,
            EQUITY,
            "Equity",
            AccountType.EQUITY,
            USD,
            Optional.empty(),
            AccountStatus.ACTIVE));
  }
}
