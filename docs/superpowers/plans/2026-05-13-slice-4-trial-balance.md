# Slice 4 — Trial Balance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a read-only `GET /reports/trial-balance` endpoint that returns one row per `(account, currency)` pair with debits, credits, balance — in both the transaction currency and the configured base currency — for entries occurring on or before an `asOf` date.

**Architecture:** Trial balance is a read-only projection over `postings` joined to `journal_entries`. No new domain aggregate. A domain-side port `TrialBalanceReadModel` lives in `domain/reports/`; the JDBC adapter does a single `GROUP BY` query under the hood. `TrialBalanceService` is a thin pass-through that exists to keep the controller domain-agnostic. The hexagonal ArchUnit rules already enforce the layering.

**Tech Stack:** Spring `JdbcClient` (the modern Spring 6.1+ fluent client; pulled in transitively by `spring-boot-starter-data-jpa`), Spring Web MVC, Testcontainers Postgres, JUnit Jupiter 6, AssertJ, MockMvc.

**Spec reference:** [`docs/superpowers/specs/2026-05-13-slices-4-6-multi-currency-and-trial-balance-design.md`](../specs/2026-05-13-slices-4-6-multi-currency-and-trial-balance-design.md), §5 (endpoint, response shape, SQL, service+controller), §9.5–9.7 (testing strategy), §10 (data migration — already done in Slice 6).

---

## File Structure

**Phase A — Domain + Application (3 commits)**

- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRow.java` — record with the projected columns and `balance()`/`baseBalance()` derivations.
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceReadModel.java` — port interface (`fetch(asOf, includeZero)`).
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceService.java` — thin facade over the port.
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRowTest.java` — record invariants + balance math.
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceServiceTest.java` — pass-through behavior with a fake port.
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java` — `@Bean TrialBalanceService trialBalanceService(...)`.

**Phase B — Persistence (1 commit)**

- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModel.java` — `JdbcClient`-backed adapter (named params, fluent style); one `GROUP BY` query.
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModelIT.java` — Testcontainers IT seeding entries and asserting row shape.

**Phase C — Web + smoke + close #15 (3 commits)**

- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceController.java`.
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/dto/TrialBalanceRowResponse.java`.
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceControllerTest.java`.
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java` — add `shouldReturnTrialBalanceForPostedEntries`.
- Modify: `docs/openapi/openapi.yaml` (regenerated).
- Modify: `README.md` — Status row + Quick Start hint at the new endpoint.

---

# Phase A — Domain + Application

Phase A introduces the domain port + service. No persistence, no web. The service is testable with a fake port. Existing build stays green throughout.

Phase A produces ~3 commits.

---

## Task 1: `TrialBalanceRow` domain record

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRow.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRowTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRowTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrialBalanceRow")
class TrialBalanceRowTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");

  @Test
  @DisplayName("balance() returns debits - credits")
  void shouldComputeBalanceFromDebitsMinusCredits() {
    TrialBalanceRow row = new TrialBalanceRow(CASH, USD, 9200L, 3200L, 10000L, 4000L);
    assertEquals(6000L, row.balance());
    assertEquals(6000L, row.baseBalance());
  }

  @Test
  @DisplayName("baseBalance() and balance() can be negative (credit-heavy)")
  void shouldReturnNegativeBalanceWhenCreditsExceedDebits() {
    TrialBalanceRow row = new TrialBalanceRow(CASH, USD, 0L, 5000L, 0L, 5000L);
    assertEquals(-5000L, row.balance());
    assertEquals(-5000L, row.baseBalance());
  }

  @Test
  @DisplayName("rejects null accountCode")
  void shouldThrowWhenAccountCodeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new TrialBalanceRow(null, USD, 0L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new TrialBalanceRow(CASH, null, 0L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative debits")
  void shouldThrowWhenDebitsAreNegative() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrialBalanceRow(CASH, USD, -1L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative credits")
  void shouldThrowWhenCreditsAreNegative() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrialBalanceRow(CASH, USD, 0L, -1L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative baseDebits")
  void shouldThrowWhenBaseDebitsAreNegative() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrialBalanceRow(CASH, USD, 0L, 0L, -1L, 0L));
  }

  @Test
  @DisplayName("rejects negative baseCredits")
  void shouldThrowWhenBaseCreditsAreNegative() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrialBalanceRow(CASH, USD, 0L, 0L, 0L, -1L));
  }

  @Test
  @DisplayName("balance() handles maximum debit accumulators without overflow")
  void shouldComputeBalanceAtMaxLongDebits() {
    // With non-negative invariants, debits - credits ∈ [-Long.MAX_VALUE, Long.MAX_VALUE],
    // so subtractExact can never throw here. We still cover the boundary as a regression.
    TrialBalanceRow allDebits = new TrialBalanceRow(CASH, USD, Long.MAX_VALUE, 0L, 0L, 0L);
    TrialBalanceRow allCredits = new TrialBalanceRow(CASH, USD, 0L, Long.MAX_VALUE, 0L, 0L);
    assertEquals(Long.MAX_VALUE, allDebits.balance());
    assertEquals(-Long.MAX_VALUE, allCredits.balance());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -B test -Dtest=TrialBalanceRowTest 2>&1 | tail -10
```

Expected: compile failure (`TrialBalanceRow` does not exist).

- [ ] **Step 3: Create the record**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceRow.java`:

```java
package co.embracejoy.accounting.keystone.domain.reports;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Currency;
import java.util.Objects;

/**
 * One row of a trial-balance projection: a single (account, transaction-currency) pair with
 * accumulated debits, credits, and the same in base currency.
 *
 * <p>This is a read model, not an aggregate. {@code balance()} and {@code baseBalance()} are
 * derived. All amount fields are non-negative integer minor units (see ADR-0003); the SQL
 * adapter uses {@code SUM(CASE WHEN side = ... THEN amount ELSE 0 END)} so each accumulator is
 * a non-negative running total.
 */
public record TrialBalanceRow(
    AccountCode accountCode,
    Currency currency,
    long debits,
    long credits,
    long baseDebits,
    long baseCredits) {

  public TrialBalanceRow {
    Objects.requireNonNull(accountCode, "accountCode");
    Objects.requireNonNull(currency, "currency");
    if (debits < 0L) {
      throw new IllegalArgumentException("debits must be non-negative; was " + debits);
    }
    if (credits < 0L) {
      throw new IllegalArgumentException("credits must be non-negative; was " + credits);
    }
    if (baseDebits < 0L) {
      throw new IllegalArgumentException("baseDebits must be non-negative; was " + baseDebits);
    }
    if (baseCredits < 0L) {
      throw new IllegalArgumentException(
          "baseCredits must be non-negative; was " + baseCredits);
    }
  }

  /** Transaction-currency balance: {@code debits - credits}. */
  public long balance() {
    return Math.subtractExact(debits, credits);
  }

  /** Base-currency balance: {@code baseDebits - baseCredits}. */
  public long baseBalance() {
    return Math.subtractExact(baseDebits, baseCredits);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -B test -Dtest=TrialBalanceRowTest 2>&1 | tail -10
```

Expected: 8 tests pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/reports/ \
        src/test/java/co/embracejoy/accounting/keystone/domain/reports/
git commit -m "$(cat <<'EOF'
feat(domain): TrialBalanceRow record for reports/trial-balance projection

A row is one (accountCode, currency) tuple with running debit/credit
totals in both transaction and base currency. balance() and
baseBalance() are derived via Math.subtractExact (fail loud on
overflow, even though SUM under non-negative inputs is bounded).

Compact constructor rejects null code/currency and negative
accumulators — SUM(CASE) over non-negative columns can't produce
negatives, but the invariant catches buggy callers fast.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `TrialBalanceReadModel` port + `TrialBalanceService` + service test

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceReadModel.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceService.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceServiceTest.java`

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceServiceTest.java`:

```java
package co.embracejoy.accounting.keystone.application.reports;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrialBalanceService")
class TrialBalanceServiceTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final LocalDate ASOF = LocalDate.parse("2026-05-13");

  @Test
  @DisplayName("query() returns the read model's rows unchanged")
  void shouldDelegateToReadModelAndReturnRowsUnchanged() {
    TrialBalanceRow row =
        new TrialBalanceRow(new AccountCode("1000"), USD, 1000L, 0L, 1000L, 0L);
    TrialBalanceReadModel fake = (asOf, includeZero) -> List.of(row);
    TrialBalanceService service = new TrialBalanceService(fake);

    List<TrialBalanceRow> rows = service.query(ASOF, false);

    assertThat(rows).containsExactly(row);
  }

  @Test
  @DisplayName("query() passes asOf and includeZero through to the read model")
  void shouldPassArgsThrough() {
    final LocalDate[] capturedAsOf = new LocalDate[1];
    final boolean[] capturedIncludeZero = new boolean[1];
    TrialBalanceReadModel spy =
        (asOf, includeZero) -> {
          capturedAsOf[0] = asOf;
          capturedIncludeZero[0] = includeZero;
          return List.of();
        };
    TrialBalanceService service = new TrialBalanceService(spy);

    service.query(ASOF, true);

    assertThat(capturedAsOf[0]).isEqualTo(ASOF);
    assertThat(capturedIncludeZero[0]).isTrue();
  }

  @Test
  @DisplayName("rejects null readModel in constructor")
  void shouldThrowWhenReadModelIsNull() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> new TrialBalanceService(null));
  }

  @Test
  @DisplayName("rejects null asOf")
  void shouldThrowWhenAsOfIsNull() {
    TrialBalanceService service = new TrialBalanceService((asOf, iz) -> List.of());
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class, () -> service.query(null, false));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -B test -Dtest=TrialBalanceServiceTest 2>&1 | tail -10
```

Expected: compile failure (`TrialBalanceReadModel` and `TrialBalanceService` don't exist).

- [ ] **Step 3: Create the port interface**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/reports/TrialBalanceReadModel.java`:

```java
package co.embracejoy.accounting.keystone.domain.reports;

import java.time.LocalDate;
import java.util.List;

/**
 * Read port for trial-balance projections.
 *
 * <p>Returns one row per {@code (accountCode, currency)} pair with at least one posting on or
 * before {@code asOf}. When {@code includeZero} is false, rows whose transaction-currency
 * balance is exactly zero are omitted. Rows are ordered by {@code accountCode}, then
 * {@code currency} — stable, predictable iteration.
 */
public interface TrialBalanceReadModel {

  /**
   * Project the trial balance.
   *
   * @param asOf only postings on journal entries with {@code occurred_on <= asOf} are counted.
   * @param includeZero when true, include rows whose transaction-currency balance is zero.
   * @return list of rows in {@code (accountCode, currency)} order; possibly empty, never null.
   */
  List<TrialBalanceRow> fetch(LocalDate asOf, boolean includeZero);
}
```

- [ ] **Step 4: Create the service**

Create `src/main/java/co/embracejoy/accounting/keystone/application/reports/TrialBalanceService.java`:

```java
package co.embracejoy.accounting.keystone.application.reports;

import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Use-case service for the trial-balance report.
 *
 * <p>Thin facade: delegates to the {@link TrialBalanceReadModel} port. The seam exists so the
 * web layer depends on application, not on infrastructure — ArchUnit enforces this in {@code
 * HexagonalArchitectureTest}.
 */
public final class TrialBalanceService {

  private final TrialBalanceReadModel readModel;

  public TrialBalanceService(TrialBalanceReadModel readModel) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
  }

  public List<TrialBalanceRow> query(LocalDate asOf, boolean includeZero) {
    Objects.requireNonNull(asOf, "asOf");
    return readModel.fetch(asOf, includeZero);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./mvnw -B test -Dtest=TrialBalanceServiceTest 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/reports/ \
        src/main/java/co/embracejoy/accounting/keystone/application/reports/ \
        src/test/java/co/embracejoy/accounting/keystone/application/reports/
git commit -m "$(cat <<'EOF'
feat(application): TrialBalanceService + TrialBalanceReadModel port

TrialBalanceReadModel is the domain-side port (functional interface,
two args: asOf, includeZero). TrialBalanceService is a thin facade —
its only real job is to keep the controller off the infrastructure
layer. The hexagonal rules already enforce that domain doesn't
depend on application or infrastructure; this interface lives in
domain/reports so the adapter implements it without inverting the
import direction.

Tests: 4 service tests (pass-through, arg capture, null guards).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Wire `TrialBalanceService` in `ApplicationConfig`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`

Phase A's last commit doesn't add a test — there's nothing testable until Phase B provides the read-model implementation. We make the wiring change here so Phase B's `@SpringBootTest` IT can autowire the service. ArchUnit + checkstyle keep this honest.

- [ ] **Step 1: Add the bean wiring**

In `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`, add the import + bean. Final file:

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import java.util.Currency;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires application-layer (domain-pure) services as Spring beans. */
@Configuration
public class ApplicationConfig {

  @Bean
  public Currency keystoneBaseCurrency(KeystoneProperties properties) {
    return properties.baseCurrency();
  }

  @Bean
  public PeriodService periodService(
      PeriodRepository periodRepository, JournalEntryRepository journalRepository) {
    return new PeriodService(periodRepository, journalRepository);
  }

  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService,
      KeystoneProperties properties) {
    return new PostJournalEntryService(
        journalRepository, accountRepository, periodService, properties.baseCurrency());
  }

  @Bean
  public AccountService accountService(AccountRepository accountRepository) {
    return new AccountService(accountRepository);
  }

  @Bean
  public TrialBalanceService trialBalanceService(TrialBalanceReadModel readModel) {
    return new TrialBalanceService(readModel);
  }
}
```

- [ ] **Step 2: Compile check**

```bash
./mvnw -B -q compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS. Spring won't be able to start the app yet (no `TrialBalanceReadModel` bean), but `compile` is just `javac` — the missing-bean error only fires at boot. Full `./mvnw -B verify` would fail at `@SpringBootTest` startup; defer until Phase B.

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java
git commit -m "$(cat <<'EOF'
feat(config): wire TrialBalanceService bean

Adds @Bean trialBalanceService(TrialBalanceReadModel) to
ApplicationConfig. Phase B introduces the JDBC read-model
implementation that satisfies this dependency; until then,
@SpringBootTest startups will fail with a missing-bean error.
Compile-only check passes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase A acceptance

3 commits, fast feedback loop. Domain + application gates green:

```bash
./mvnw -B test -Dtest=TrialBalanceRowTest,TrialBalanceServiceTest 2>&1 | tail -10
```

Expected: all green.

**Do not** open a PR after Phase A. Phase A ships with Phase B as a single PR so `main` is never in a broken state (missing read-model bean).

---

# Phase B — JDBC read-model adapter + integration test

Phase B implements `TrialBalanceReadModel` against Postgres via `JdbcClient` (Spring 6.1+ fluent client, auto-configured by Spring Boot whenever a `DataSource` is on the classpath). One commit.

---

## Task 4: `TrialBalanceJdbcReadModel` adapter + integration test

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModel.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModelIT.java`

- [ ] **Step 1: Write the failing IT**

Create `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModelIT.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.reports;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.persistence.account.AccountRepositoryAdapter;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency EUR = Currency.getInstance("EUR");

  // Use codes outside the V4 seed range (1000, 1100, 3000, 4000) to avoid conflicts.
  private static final AccountCode CASH_USD = new AccountCode("8000");
  private static final AccountCode CASH_EUR = new AccountCode("8000-EUR");
  private static final AccountCode REVENUE = new AccountCode("8900");

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

    List<TrialBalanceRow> withZeros =
        readModel.fetch(LocalDate.parse("2026-05-13"), true);
    List<TrialBalanceRow> withoutZeros =
        readModel.fetch(LocalDate.parse("2026-05-13"), false);

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
    if (accountRepo.findByCode(CASH_USD).isEmpty()) {
      accountRepo.save(
          new Account(
              CASH_USD,
              "Cash USD",
              AccountType.ASSET,
              USD,
              Optional.empty(),
              AccountStatus.ACTIVE));
    }
    if (accountRepo.findByCode(REVENUE).isEmpty()) {
      accountRepo.save(
          new Account(
              REVENUE,
              "Revenue",
              AccountType.REVENUE,
              USD,
              Optional.empty(),
              AccountStatus.ACTIVE));
    }
  }

  private void seedEntry(
      LocalDate occurredOn, long minorUnits, Currency currency, AccountCode debit, AccountCode credit) {
    Money m = new Money(minorUnits, currency);
    JournalEntry entry =
        new JournalEntry(
            occurredOn,
            "seed " + occurredOn,
            List.of(
                new Posting(debit, Side.DEBIT, m, m), new Posting(credit, Side.CREDIT, m, m)));
    journalRepo.save(entry);
  }
}
```

- [ ] **Step 2: Run the IT to verify it fails**

```bash
./mvnw -B test -Dtest=TrialBalanceJdbcReadModelIT 2>&1 | tail -10
```

Expected: compile failure (`TrialBalanceJdbcReadModel` does not exist).

- [ ] **Step 3: Create the adapter**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/TrialBalanceJdbcReadModel.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.reports;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link TrialBalanceReadModel}.
 *
 * <p>One GROUP BY query against {@code postings} joined to {@code journal_entries}; rows are
 * filtered by {@code je.occurred_on <= :asOf} and (optionally) {@code balance != 0}. Uses
 * Spring 6.1+ {@link JdbcClient} (the modern fluent client) with named parameters — the
 * {@code :includeZero} flag is referenced once in the {@code HAVING} clause and Spring binds
 * it for us.
 */
@Repository
@Transactional(readOnly = true)
public class TrialBalanceJdbcReadModel implements TrialBalanceReadModel {

  private static final String SQL =
      """
      SELECT p.account_code,
             p.currency,
             SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units  ELSE 0 END) AS debits,
             SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units  ELSE 0 END) AS credits,
             SUM(CASE WHEN p.side = 'DEBIT'  THEN p.base_minor_units    ELSE 0 END) AS base_debits,
             SUM(CASE WHEN p.side = 'CREDIT' THEN p.base_minor_units    ELSE 0 END) AS base_credits
      FROM   postings p
      JOIN   journal_entries je ON je.id = p.journal_entry_id
      WHERE  je.occurred_on <= :asOf
      GROUP  BY p.account_code, p.currency
      HAVING :includeZero OR (SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units ELSE 0 END)
                            - SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units ELSE 0 END)) <> 0
      ORDER  BY p.account_code, p.currency
      """;

  private static final RowMapper<TrialBalanceRow> MAPPER =
      (rs, rowNum) ->
          new TrialBalanceRow(
              new AccountCode(rs.getString("account_code")),
              Currency.getInstance(rs.getString("currency")),
              rs.getLong("debits"),
              rs.getLong("credits"),
              rs.getLong("base_debits"),
              rs.getLong("base_credits"));

  private final JdbcClient jdbc;

  public TrialBalanceJdbcReadModel(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<TrialBalanceRow> fetch(LocalDate asOf, boolean includeZero) {
    return jdbc.sql(SQL)
        .param("asOf", asOf)
        .param("includeZero", includeZero)
        .query(MAPPER)
        .list();
  }
}
```

- [ ] **Step 4: Run the IT to verify it passes**

Make sure Postgres is up locally (or let Testcontainers spin one up — `@ServiceConnection` does the work):

```bash
./mvnw -B test -Dtest=TrialBalanceJdbcReadModelIT 2>&1 | tail -15
```

Expected: 5 tests pass.

- [ ] **Step 5: Run the full local gate**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. With Phase A's `TrialBalanceService` bean now wirable (Phase B provides the read-model), the existing `@SpringBootTest` ITs start up cleanly.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/ \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/reports/
git commit -m "$(cat <<'EOF'
feat(persistence): TrialBalanceJdbcReadModel adapter + IT

JdbcClient-backed implementation of TrialBalanceReadModel (Spring
6.1+ fluent client, auto-configured by Spring Boot). One GROUP BY
query against postings joined to journal_entries, filtering by
occurred_on <= :asOf and (optionally) by balance != 0. Named
parameters keep the HAVING clause readable.

IT (Testcontainers Postgres) seeds entries and asserts:
- non-zero rows only by default, sorted by (accountCode, currency)
- asOf filter excludes later postings
- multi-currency entries split into per-(account, currency) rows
- includeZero=true surfaces net-zero rows
- empty input → empty output

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B acceptance

`./mvnw -B verify` green. 4 commits accumulated (3 from Phase A + 1 from Phase B). At this point the service is fully wired and queryable, but no HTTP surface. Continue directly to Phase C; do not open a PR after Phase B alone — the slice is one PR.

---

# Phase C — Web layer + smoke + close #15

Phase C adds the controller, DTO, controller test, a smoke case, and the OpenAPI regen. ~3 commits, then PR.

---

## Task 5: `TrialBalanceRowResponse` DTO + `TrialBalanceController` + controller test

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/dto/TrialBalanceRowResponse.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceController.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

Create `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceControllerTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web.reports;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrialBalanceController.class)
@DisplayName("TrialBalanceController")
class TrialBalanceControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean TrialBalanceService service;

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  @DisplayName("GET /reports/trial-balance returns 200 with rows from the service")
  void shouldReturn200WithRows() throws Exception {
    TrialBalanceRow cash =
        new TrialBalanceRow(new AccountCode("1000"), USD, 10000L, 0L, 10000L, 0L);
    TrialBalanceRow rev =
        new TrialBalanceRow(new AccountCode("4000"), USD, 0L, 10000L, 0L, 10000L);
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of(cash, rev));

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].accountCode").value("1000"))
        .andExpect(jsonPath("$[0].currency").value("USD"))
        .andExpect(jsonPath("$[0].debits").value(10000))
        .andExpect(jsonPath("$[0].credits").value(0))
        .andExpect(jsonPath("$[0].balance").value(10000))
        .andExpect(jsonPath("$[0].baseDebits").value(10000))
        .andExpect(jsonPath("$[0].baseCredits").value(0))
        .andExpect(jsonPath("$[0].baseBalance").value(10000))
        .andExpect(jsonPath("$[1].accountCode").value("4000"))
        .andExpect(jsonPath("$[1].balance").value(-10000));
  }

  @Test
  @DisplayName("defaults asOf to today (UTC) when no query param given")
  void shouldDefaultAsOfToTodayWhenMissing() throws Exception {
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance")).andExpect(status().isOk());

    ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
    Mockito.verify(service).query(captor.capture(), Mockito.eq(false));
    // Allow ± 1 day for UTC vs. local timezone skew on the runner.
    LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .isBetween(today.minusDays(1), today.plusDays(1));
  }

  @Test
  @DisplayName("passes includeZero=true through to the service")
  void shouldPassIncludeZeroThrough() throws Exception {
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13&includeZero=true"))
        .andExpect(status().isOk());

    Mockito.verify(service).query(LocalDate.parse("2026-05-13"), true);
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when asOf is malformed")
  void shouldReturn400WhenAsOfMalformed() throws Exception {
    mvc.perform(get("/reports/trial-balance?asOf=not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("returns 200 with empty array when service returns no rows")
  void shouldReturn200WithEmptyArrayWhenNoRows() throws Exception {
    Mockito.when(service.query(Mockito.any(LocalDate.class), Mockito.anyBoolean()))
        .thenReturn(List.of());

    mvc.perform(get("/reports/trial-balance?asOf=2026-05-13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -B test -Dtest=TrialBalanceControllerTest 2>&1 | tail -10
```

Expected: compile failure (`TrialBalanceController` doesn't exist).

- [ ] **Step 3: Create the response DTO**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/dto/TrialBalanceRowResponse.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web.reports.dto;

import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;

/**
 * Outbound wire shape for one trial-balance row.
 *
 * <p>{@code balance} and {@code baseBalance} are flattened onto the wire (rather than expecting
 * clients to subtract debits and credits themselves). Amount fields are integer minor units;
 * see ADR-0003.
 */
public record TrialBalanceRowResponse(
    String accountCode,
    String currency,
    long debits,
    long credits,
    long balance,
    long baseDebits,
    long baseCredits,
    long baseBalance) {

  public static TrialBalanceRowResponse of(TrialBalanceRow row) {
    return new TrialBalanceRowResponse(
        row.accountCode().value(),
        row.currency().getCurrencyCode(),
        row.debits(),
        row.credits(),
        row.balance(),
        row.baseDebits(),
        row.baseCredits(),
        row.baseBalance());
  }
}
```

- [ ] **Step 4: Create the controller**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/TrialBalanceController.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web.reports;

import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.infrastructure.web.reports.dto.TrialBalanceRowResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /reports/trial-balance} — read-only projection of postings, one row per
 * {@code (accountCode, currency)} pair.
 *
 * <p>Query params:
 *
 * <ul>
 *   <li>{@code asOf} — ISO date; defaults to today (UTC).
 *   <li>{@code includeZero} — boolean; defaults to {@code false} (suppress net-zero rows).
 * </ul>
 */
@RestController
@RequestMapping("/reports")
public class TrialBalanceController {

  private final TrialBalanceService service;

  public TrialBalanceController(TrialBalanceService service) {
    this.service = service;
  }

  @GetMapping("/trial-balance")
  public List<TrialBalanceRowResponse> get(
      @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf,
      @RequestParam(value = "includeZero", required = false, defaultValue = "false")
          boolean includeZero) {
    LocalDate effective = (asOf != null) ? asOf : LocalDate.now(ZoneOffset.UTC);
    return service.query(effective, includeZero).stream().map(TrialBalanceRowResponse::of).toList();
  }
}
```

- [ ] **Step 5: Run the controller test to verify it passes**

```bash
./mvnw -B test -Dtest=TrialBalanceControllerTest 2>&1 | tail -10
```

Expected: 5 tests pass.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/ \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/reports/
git commit -m "$(cat <<'EOF'
feat(web): TrialBalanceController + DTO + tests

GET /reports/trial-balance: optional asOf (defaults to today UTC),
optional includeZero (defaults to false). Returns a JSON array of
TrialBalanceRowResponse — accountCode, currency, debits, credits,
balance, baseDebits, baseCredits, baseBalance — sorted by
(accountCode, currency).

Five MockMvc tests cover: happy path with two rows, default asOf,
includeZero=true passes through, malformed asOf → 400 ProblemDetail,
empty result.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extend `ApplicationSmokeIT` with a trial-balance case

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

- [ ] **Step 1: Append the new test**

In `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`, append before the final closing `}`:

```java
  @Test
  @DisplayName("GET /reports/trial-balance returns rows for a posted entry; sums net to zero in base")
  void shouldReturnTrialBalanceForPostedEntries() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    // Post a balanced USD entry (using the seeded 1000 and 3000 accounts).
    ResponseEntity<String> post =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(trialBalanceSeedBody())
            .retrieve()
            .toEntity(String.class);
    assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String body =
        client
            .get()
            .uri("/reports/trial-balance?asOf=2026-07-31&includeZero=true")
            .retrieve()
            .body(String.class);

    assertThat(body).isNotNull();
    // Both legs appear in the report; both carry baseBalance.
    assertThat(body).contains("\"accountCode\":\"1000\"").contains("\"accountCode\":\"3000\"");
    assertThat(body).contains("\"currency\":\"USD\"");
    assertThat(body).contains("\"balance\":4444").contains("\"balance\":-4444");
  }

  private static String trialBalanceSeedBody() {
    return """
        {
          "occurredOn": "2026-07-15",
          "description": "trial balance smoke seed",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 4444,
              "currency": "USD", "baseMinorUnits": 4444 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 4444,
              "currency": "USD", "baseMinorUnits": 4444 }
          ]
        }
        """;
  }
```

A July date is used so the entry doesn't collide with the May/June postings made by other smoke tests, and so the period close/reopen test stays isolated.

- [ ] **Step 2: Run the smoke test**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java
git commit -m "$(cat <<'EOF'
test(smoke): ApplicationSmokeIT covers GET /reports/trial-balance

Posts a balanced USD entry in July (avoids collision with the
May/June smoke fixtures), then GET /reports/trial-balance with
includeZero=true. Asserts both legs (1000 + 3000) appear; the
balance on one is +4444 and the other is -4444. Sums to zero
in base, end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Regenerate OpenAPI + README + final verify; closes #15

**Files:**
- Modify: `docs/openapi/openapi.yaml`
- Modify: `README.md`

- [ ] **Step 1: Regenerate the OpenAPI snapshot**

Ensure local Postgres on port 5434 is running (`docker compose up -d postgres` from repo root if not). Then:

```bash
./mvnw -B verify -Popenapi-update -Dopenapi.diff.skip=true 2>&1 | tail -10
```

Expected: BUILD SUCCESS. `docs/openapi/openapi.yaml` now lists `GET /reports/trial-balance`.

Confirm by `git diff docs/openapi/openapi.yaml` — you should see a new `paths./reports/trial-balance.get` section with `asOf` + `includeZero` parameters.

- [ ] **Step 2: Update `README.md` status row + Quick Start hint**

Edit `README.md`. Add a Status row directly under the Slice 6 row:

```markdown
- [x] Slice 4 — trial balance (#15)
```

Below the existing `curl … /journal-entries` example in the Quick Start section, add:

```markdown
And after posting some entries, get the trial balance:

\```bash
curl -s 'http://localhost:8080/reports/trial-balance?asOf=2026-05-13' | jq
\```
```

(Drop the `\`'s; they're escaped here because this plan file is itself a Markdown fence.)

- [ ] **Step 3: Final cold-cache verify (no `-Dopenapi.diff.skip`)**

`GET /reports/trial-balance` is **additive** — it doesn't change any existing endpoint or schema — so Layer 4 (openapi-diff vs main) is not breaking. Run the full gate including the diff:

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -30
```

Expected: BUILD SUCCESS. JaCoCo ≥ 85%, PIT ≥ 60% on `domain..` + `application..`.

If Layer 4 fails unexpectedly (because openapi-diff considers any added required property a breaking change in some configurations), drop `-Dopenapi.diff.skip=true` only if you've confirmed the additions are genuinely backwards-compatible; do not push without checking.

- [ ] **Step 4: Apply Spotless and commit (closes #15)**

```bash
./mvnw -B spotless:apply -q
git add docs/openapi/openapi.yaml README.md
git commit -m "$(cat <<'EOF'
docs: Slice 4 done — flip status, Quick Start hint, regenerate OpenAPI

README Status flips Slice 4 to ✅ and the Quick Start gets a
trial-balance curl example. docs/openapi/openapi.yaml regenerated;
GET /reports/trial-balance is purely additive — no existing
endpoint/schema changes — so the openapi-diff Layer 4 check
passes without the breaking-change-approved label.

Closes #15

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

3 commits in Phase C (Tasks 5, 6, 7). 7 commits total across the slice. PR title:

`Slice 4: trial balance read endpoint (closes #15)`

No special PR label needed — the OpenAPI change is additive.

After merge:
- Issue #15 closes.
- `main` exposes `GET /reports/trial-balance`.
- Smoke covers the round trip.

---

## Slice 4 overall acceptance

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on the PR.
2. `GET /reports/trial-balance` (no params) returns 200 with today (UTC) as `asOf`, suppressing zero-balance rows by default.
3. `GET /reports/trial-balance?asOf=YYYY-MM-DD&includeZero=true` accepts both params and round-trips them to the service.
4. Multi-currency entries split into per-`(accountCode, currency)` rows; each row carries both transaction-currency and base-currency totals.
5. ApplicationSmokeIT proves end-to-end that posting an entry surfaces in the report.
6. `docs/openapi/openapi.yaml` lists the new endpoint with both parameters and the response schema.
7. Issue #15 closes when the PR merges.
