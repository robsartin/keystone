package co.embracejoy.accounting.keystone.infrastructure.persistence.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.persistence.account.AccountRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.persistence.security.TenantUserRoleRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy.TenantRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves that Postgres Row-Level Security enforces tenant isolation on {@code accounts}, {@code
 * journal_entries}, {@code postings}, {@code periods}, and {@code tenant_user_roles} independent of
 * the application-side {@code TenantContext} filtering.
 *
 * <p>Seeding goes through the tenant-aware adapters (which stamp {@code tenant_id} and invoke
 * {@code RlsTransactionInterceptor}), using the app's own superuser Testcontainers connection.
 *
 * <p>Assertions, however, run raw SQL through a <em>separate, unprivileged</em> JDBC connection
 * ({@link #rlsJdbc}) authenticated as a dedicated Postgres role with {@code NOSUPERUSER} and no
 * {@code BYPASSRLS} — bypassing the adapters entirely and, critically, bypassing the app pool's
 * superuser privileges too. Without this, RLS would be silently skipped: Postgres exempts table
 * owners and superusers from RLS policies, and the Testcontainers default role is a superuser. See
 * ADR-0016, which calls for the app pool itself to run as such a restricted role in production.
 */
@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@DisplayName("Row-Level Security (integration)")
class RowLevelSecurityIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  private static final String RLS_ROLE = "rls_probe";
  private static final String RLS_PASSWORD = "rls_probe";

  @Autowired TenantContext tenantContext;
  @Autowired TenantRepositoryAdapter tenantRepo;
  @Autowired AccountRepositoryAdapter accountRepo;
  @Autowired JournalEntryRepository journalRepo;
  @Autowired PeriodService periodService;
  @Autowired TenantUserRoleRepositoryAdapter tenantUserRoleRepo;

  private static JdbcClient rlsJdbc;
  private static PlatformTransactionManager rlsTxManager;
  private static boolean rlsRoleReady;

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT_A = Tenants.DEFAULT_TENANT_ID;

  // Unique per test-method instance — naturally isolates this test's rows from other tests
  // sharing the class-level Testcontainers Postgres instance.
  private TenantId tenantB;
  private String tag;

  /**
   * Creates the unprivileged probe role and its dedicated {@code DataSource} the first time any
   * test runs. This can't be {@code @BeforeAll}: Spring only runs Flyway (which creates the tables
   * the {@code GRANT} below targets) when the test's application context is built, and a static
   * {@code @BeforeAll} method runs before that context exists.
   *
   * <p>The Testcontainers default role is a Postgres superuser, which bypasses RLS entirely
   * (superusers and table owners are exempt) — this role is {@code NOSUPERUSER}/{@code NOBYPASSRLS}
   * so RLS policies are actually exercised, matching ADR-0016's expectation that the app pool
   * itself runs as such a restricted role in production.
   */
  private static synchronized void ensureRlsRole() {
    if (rlsRoleReady) {
      return;
    }
    SimpleDriverDataSource superuserDataSource = new SimpleDriverDataSource();
    superuserDataSource.setDriverClass(Driver.class);
    superuserDataSource.setUrl(postgres.getJdbcUrl());
    superuserDataSource.setUsername(postgres.getUsername());
    superuserDataSource.setPassword(postgres.getPassword());
    JdbcClient superuserJdbc = JdbcClient.create(superuserDataSource);

    superuserJdbc
        .sql(
            "CREATE ROLE "
                + RLS_ROLE
                + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"
                + RLS_PASSWORD
                + "'")
        .update();
    superuserJdbc
        .sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + RLS_ROLE)
        .update();

    SimpleDriverDataSource rlsDataSource = new SimpleDriverDataSource();
    rlsDataSource.setDriverClass(Driver.class);
    rlsDataSource.setUrl(postgres.getJdbcUrl());
    rlsDataSource.setUsername(RLS_ROLE);
    rlsDataSource.setPassword(RLS_PASSWORD);

    rlsJdbc = JdbcClient.create(rlsDataSource);
    rlsTxManager = new DataSourceTransactionManager(rlsDataSource);
    rlsRoleReady = true;
  }

  @BeforeEach
  void seed() {
    ensureRlsRole();
    tenantB = new TenantId(UUID.randomUUID());
    tag = UUID.randomUUID().toString().substring(0, 8);

    // tenants table is NOT RLS-protected, so no GUC/context needed for this insert.
    tenantRepo.save(new Tenant(tenantB, "Tenant B " + tag, Instant.now(), Optional.empty()));

    tenantContext.set(TENANT_A);
    saveAccount(TENANT_A, "A-" + tag, "A Cash");

    tenantContext.set(tenantB);
    saveAccount(tenantB, "B-" + tag, "B Cash");
  }

  @Test
  @DisplayName("shouldReturnZeroRowsWhenGucIsUnset")
  void shouldReturnZeroRowsWhenGucIsUnset() {
    Long count =
        inTxWithGuc(
            null, () -> rlsJdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single());
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("shouldReturnOnlyMyTenantsRowsWhenGucIsSet")
  void shouldReturnOnlyMyTenantsRowsWhenGucIsSet() {
    // TENANT_A is the shared well-known default tenant, so other tests' (and V4's seed) rows
    // are also visible under its GUC — filter to this test's own tagged codes to isolate them.
    List<String> codesForA =
        inTxWithGuc(
                TENANT_A.value().toString(),
                () -> rlsJdbc.sql("SELECT code FROM accounts").query(String.class).list())
            .stream()
            .filter(code -> code.endsWith(tag))
            .toList();
    assertThat(codesForA).containsExactly("A-" + tag);

    // tenantB is a fresh UUID created in seed(), so it has exactly this test's one row.
    List<String> codesForB =
        inTxWithGuc(
            tenantB.value().toString(),
            () -> rlsJdbc.sql("SELECT code FROM accounts").query(String.class).list());
    assertThat(codesForB).containsExactly("B-" + tag);
  }

  @Test
  @DisplayName("shouldReturnZeroRowsWhenGucIsBogus")
  void shouldReturnZeroRowsWhenGucIsBogus() {
    String bogusTenant = UUID.randomUUID().toString();
    Long count =
        inTxWithGuc(
            bogusTenant,
            () -> rlsJdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single());
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("shouldRejectInsertWithWrongTenantId")
  void shouldRejectInsertWithWrongTenantId() {
    // Spring's exception translator resolves this to different DataAccessException subtypes
    // depending on JDBC driver metadata (observed: BadSqlGrammarException, not the more intuitive
    // DataIntegrityViolationException); the WITH CHECK violation is always present in the root
    // Postgres SQLException, so assert on that rather than the translated type or its message.
    DataAccessException thrown =
        (DataAccessException)
            catchThrowable(
                () ->
                    inTxWithGuc(
                        TENANT_A.value().toString(),
                        () -> {
                          rlsJdbc
                              .sql(
                                  "INSERT INTO accounts (tenant_id, code, name, type, currency, active) "
                                      + "VALUES ('"
                                      + tenantB.value()
                                      + "', 'X-"
                                      + tag
                                      + "', 'Rejected', 'ASSET', 'USD', true)")
                              .update();
                          return null;
                        }));
    assertThat(thrown).isInstanceOf(DataAccessException.class);
    assertThat(thrown.getMostSpecificCause()).hasMessageContaining("row-level security");
  }

  @Test
  @DisplayName("shouldEnforceIsolationOnJournalEntriesAndPostings")
  void shouldEnforceIsolationOnJournalEntriesAndPostings() {
    tenantContext.set(TENANT_A);
    saveEntry(TENANT_A, "A-" + tag);

    tenantContext.set(tenantB);
    saveEntry(tenantB, "B-" + tag);

    // TENANT_A is the shared well-known default tenant, so other tests' journal entries are also
    // visible under its GUC — scope the count to this test's own tagged postings via the join.
    assertThat(countJournalEntriesForSuffix(TENANT_A.value().toString(), "A-" + tag)).isEqualTo(1L);
    assertThat(countRowsForCode(TENANT_A.value().toString(), "postings", "A-" + tag)).isEqualTo(2L);

    // tenantB is a fresh UUID created in seed(), so it only ever has this test's rows.
    assertThat(countJournalEntriesForSuffix(tenantB.value().toString(), "B-" + tag)).isEqualTo(1L);
    assertThat(countRowsForCode(tenantB.value().toString(), "postings", "B-" + tag)).isEqualTo(2L);
  }

  @Test
  @DisplayName("shouldEnforceIsolationOnPeriods")
  void shouldEnforceIsolationOnPeriods() {
    YearMonth month = YearMonth.from(LocalDate.now()).minusMonths(1);

    tenantContext.set(TENANT_A);
    saveEntry(TENANT_A, "PA-" + tag, month.atDay(1));
    Result<?, ?> closeA = periodService.close(TENANT_A, month, "root");
    assertThat(closeA).isInstanceOf(Result.Success.class);

    tenantContext.set(tenantB);
    saveEntry(tenantB, "PB-" + tag, month.atDay(1));
    Result<?, ?> closeB = periodService.close(tenantB, month, "root");
    assertThat(closeB).isInstanceOf(Result.Success.class);

    assertThat(countPeriods(TENANT_A.value().toString(), month)).isEqualTo(1L);
    assertThat(countPeriods(tenantB.value().toString(), month)).isEqualTo(1L);
  }

  @Test
  @DisplayName("shouldEnforceIsolationOnTenantUserRoles")
  void shouldEnforceIsolationOnTenantUserRoles() {
    tenantContext.set(TENANT_A);
    tenantUserRoleRepo.grant(
        new TenantUserRole(TENANT_A, "auth0|a-" + tag, Role.ADMIN, Instant.now(), "auth0|root"));

    tenantContext.set(tenantB);
    tenantUserRoleRepo.grant(
        new TenantUserRole(tenantB, "auth0|b-" + tag, Role.ADMIN, Instant.now(), "auth0|root"));

    assertThat(countUserSub(TENANT_A.value().toString(), "auth0|a-" + tag)).isEqualTo(1L);
    assertThat(countUserSub(TENANT_A.value().toString(), "auth0|b-" + tag)).isZero();
    assertThat(countUserSub(tenantB.value().toString(), "auth0|b-" + tag)).isEqualTo(1L);
  }

  // ---- helpers ----

  private long countPeriods(String tenantUuid, YearMonth month) {
    return inTxWithGuc(
        tenantUuid,
        () ->
            rlsJdbc
                .sql("SELECT COUNT(*) FROM periods WHERE year_month = ?")
                .param(month.toString())
                .query(Long.class)
                .single());
  }

  private long countUserSub(String tenantUuid, String userSub) {
    return inTxWithGuc(
        tenantUuid,
        () ->
            rlsJdbc
                .sql("SELECT COUNT(*) FROM tenant_user_roles WHERE user_sub = ?")
                .param(userSub)
                .query(Long.class)
                .single());
  }

  private void saveAccount(TenantId tenantId, String code, String name) {
    Result<Account, ?> result =
        accountRepo.save(
            new Account(
                tenantId,
                new AccountCode(code),
                name,
                AccountType.ASSET,
                USD,
                Optional.empty(),
                AccountStatus.ACTIVE));
    assertThat(result).isInstanceOf(Result.Success.class);
  }

  private void saveEntry(TenantId tenantId, String suffix) {
    saveEntry(tenantId, suffix, LocalDate.now());
  }

  private void saveEntry(TenantId tenantId, String suffix, LocalDate occurredOn) {
    saveAccount(tenantId, "CASH-" + suffix, "Cash " + suffix);
    saveAccount(tenantId, "REV-" + suffix, "Revenue " + suffix);
    Money amount = new Money(1000L, USD);
    Posting debit = new Posting(new AccountCode("CASH-" + suffix), Side.DEBIT, amount, amount);
    Posting credit = new Posting(new AccountCode("REV-" + suffix), Side.CREDIT, amount, amount);
    Result<JournalEntry, ?> entry =
        JournalEntry.of(tenantId, occurredOn, "seed " + suffix, List.of(debit, credit));
    assertThat(entry).isInstanceOf(Result.Success.class);
    journalRepo.save(((Result.Success<JournalEntry, ?>) entry).value());
  }

  private long countRowsForCode(String tenantUuid, String table, String accountCodeSuffix) {
    return inTxWithGuc(
        tenantUuid,
        () ->
            rlsJdbc
                .sql("SELECT COUNT(*) FROM " + table + " WHERE account_code LIKE ?")
                .param("%" + accountCodeSuffix)
                .query(Long.class)
                .single());
  }

  /**
   * Counts distinct journal entries that have at least one posting whose account code ends with
   * {@code suffix}. {@code journal_entries} carries no account code of its own, so this joins
   * through {@code postings} to scope the count to rows this test seeded — the shared default
   * tenant otherwise accumulates entries from every other test in this class.
   */
  private long countJournalEntriesForSuffix(String tenantUuid, String suffix) {
    return inTxWithGuc(
        tenantUuid,
        () ->
            rlsJdbc
                .sql(
                    "SELECT COUNT(DISTINCT je.id) FROM journal_entries je "
                        + "JOIN postings p ON p.journal_entry_id = je.id "
                        + "WHERE p.account_code LIKE ?")
                .param("%" + suffix)
                .query(Long.class)
                .single());
  }

  /**
   * Runs {@code action} inside a fresh transaction, optionally setting {@code app.current_tenant}
   * via {@code SET LOCAL} first. {@code SET LOCAL} scopes to the current transaction and resets at
   * commit/rollback, so each call gets a clean GUC state regardless of connection-pool reuse.
   */
  private <T> T inTxWithGuc(String tenantUuid, Supplier<T> action) {
    TransactionTemplate tt = new TransactionTemplate(rlsTxManager);
    return tt.execute(
        status -> {
          if (tenantUuid != null) {
            rlsJdbc.sql("SET LOCAL app.current_tenant = '" + tenantUuid + "'").update();
          }
          return action.get();
        });
  }
}
