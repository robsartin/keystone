# Slice 6 — Multi-Currency Journal Entries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drop the single-currency-per-entry rule. Each `Posting` carries a transaction-currency `amount` and a base-currency `baseAmount`. `JournalEntry.of(...)` validates that entries balance in **base** currency (configured globally via `keystone.base-currency`). Existing single-USD data is backfilled cleanly via Flyway V5.

**Architecture:** Three phases, one PR per phase. Phase A makes the type changes (adds `Posting.baseAmount`, drops `JournalEntry.currency`, adds `BaseCurrencyMismatch`, extends `JournalValidationContext` with `baseCurrency`). Phase B rewrites `JournalEntry.of(...)` validation, lands Flyway V5, updates JPA entities/mapper/IT, and wires `KeystoneProperties` into the service. Phase C ships the breaking wire-format change, regenerates the OpenAPI snapshot, and extends the smoke IT.

**Tech Stack:** Same as Slices 1-5 — Spring Boot 4.0.3 on Java 25, JPA + Postgres + Flyway, Testcontainers, MockMvc. New `KeystoneProperties` record uses `@ConfigurationProperties`. No new dependencies.

**Pre-condition:** `main` has Slices 2 + 3 merged and the combined spec (#49) on `main`. Branch `17-slice-6-phase-a-domain` is checked out for Phase A.

**Spec authority:** `docs/superpowers/specs/2026-05-13-slices-4-6-multi-currency-and-trial-balance-design.md`. When the spec and this plan disagree, the spec wins.

**Definition of done (entire slice):**

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every Phase PR.
2. Phase C's PR carries the `breaking-change-approved` label (or otherwise satisfies the openapi-diff Layer 4 gate). Snapshot drift is expected and intentional.
3. `POST /journal-entries` with a multi-currency entry that balances in base returns 201; per-posting `currency` + `baseMinorUnits` are required in the request body.
4. `POST /journal-entries` with a posting whose `baseAmount.currency` ≠ `keystone.base-currency` returns 400 + `/problems/journal/base-currency-mismatch`.
5. `POST /journal-entries` with base amounts that don't balance returns 400 + `/problems/journal/unbalanced` (the existing variant — now interpreted in base currency).
6. ADR-0014 committed; ADR README updated. `docs/openapi/openapi.yaml` regenerated to reflect the new wire format.
7. Issue #17 closes when Phase C merges.

---

## File Structure

**Created or modified in Slice 6:**

| Path | Phase | Responsibility |
|---|---|---|
| `docs/adr/0014-multi-currency-base-anchoring.md` | A | ADR-0014 |
| `docs/adr/README.md` | A | flip 0014 to Accepted |
| `src/main/java/.../config/KeystoneProperties.java` | A | `@ConfigurationProperties("keystone")` carrying base currency |
| `src/main/java/.../domain/journal/JournalError.java` | A | + `BaseCurrencyMismatch` variant |
| `src/main/java/.../domain/journal/JournalValidationContext.java` | A | + `Currency baseCurrency` field |
| `src/main/java/.../domain/journal/Posting.java` | A | + `Money baseAmount` field (BREAKING — 4-arg record) |
| `src/main/java/.../domain/journal/JournalEntry.java` | A | drop `currency` field from record (BREAKING) |
| `src/main/java/.../infrastructure/web/ResultMapper.java` | A | handle `BaseCurrencyMismatch` (sealed switch must stay compiling) |
| Tests for Posting, JournalEntry, JournalValidationContext, JournalError | A | updated for new shapes |
| All Posting/JournalEntry call sites in tests + main source | A | updated for new constructor signatures |
| `src/main/java/.../infrastructure/persistence/journal/JournalEntryEntity.java` | B | drop `currency` column |
| `src/main/java/.../infrastructure/persistence/journal/PostingEntity.java` | B | add `currency` + `base_minor_units` columns |
| `src/main/java/.../infrastructure/persistence/journal/JournalEntryEntityMapper.java` | B | map per-posting currency + baseAmount; drop entry-level currency |
| `src/main/resources/db/migration/V5__postings_multi_currency.sql` | B | the meaty migration |
| `src/main/java/.../domain/journal/JournalEntry.java` | B | `of(ctx)` validation rewritten |
| `src/main/java/.../application/journal/PostJournalEntryService.java` | B | take `Currency baseCurrency` constructor arg; pack into ctx |
| `src/main/java/.../infrastructure/config/ApplicationConfig.java` | B | wire `KeystoneProperties` into `PostJournalEntryService` |
| `src/main/resources/application.yaml` | B | `keystone.base-currency: USD` |
| Existing IT files (e.g., `JpaJournalEntryRepositoryIT`, `ApplicationSmokeIT`) | B | updated for multi-currency |
| `src/main/java/.../infrastructure/web/dto/PostJournalEntryRequest.java` | C | drop top-level `currency` |
| `src/main/java/.../infrastructure/web/dto/PostingRequest.java` | C | + `currency` + `baseMinorUnits` |
| `src/main/java/.../infrastructure/web/dto/JournalEntryResponse.java` | C | drop top-level `currency` |
| `src/main/java/.../infrastructure/web/dto/PostingResponse.java` | C | + `currency` + `baseMinorUnits` |
| `src/main/java/.../infrastructure/web/JournalEntryController.java` | C | pass `baseAmount` through to domain |
| `src/test/java/.../infrastructure/web/JournalEntryControllerTest.java` | C | update for new wire shape + new failure |
| `src/test/java/.../smoke/ApplicationSmokeIT.java` | C | + multi-currency test |
| `docs/openapi/openapi.yaml` | C | regenerated |
| `README.md` + `CLAUDE.md` | C | Slice 6 status + multi-currency convention |

---

# Phase A — Domain type changes (breaking but contained)

Phase A makes the breaking type changes to `Posting` and `JournalEntry` plus adds the new `BaseCurrencyMismatch` variant and `KeystoneProperties` config. Every commit leaves the build compiling and tests passing — but Posting's signature changes mid-phase, so callers update in the same commit as the signature change.

Phase A produces 7 commits.

---

## Task 1: ADR-0014 — multi-currency base anchoring

**Files:**
- Create: `docs/adr/0014-multi-currency-base-anchoring.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0014-multi-currency-base-anchoring.md`:

```markdown
# ADR-0014: Multi-currency journal entries with base-currency anchoring

- **Status:** Accepted
- **Date:** 2026-05-13

## Context

Slice 6 removes the single-currency-per-entry rule that Plan 1
established. Real ledgers need cross-currency transactions —
e.g., a USD→EUR transfer is one entry with two postings in
different currencies. The challenge: how do you say "this entry
balances" when its postings are in different currencies?

The accounting answer is: balance in a **base** currency (also
called functional currency). Every posting carries an amount in
the transaction currency *and* an equivalent in the base
currency. The entry balances in base.

## Decision

- **Each `Posting`** carries `(amount, baseAmount)` — both are
  `Money` records. `amount.currency()` is the transaction
  currency; `baseAmount.currency()` is the configured base.
- **Base currency is configured globally** at deploy time via
  `keystone.base-currency: USD` (env override
  `KEYSTONE_BASE_CURRENCY`). One base per running app; can't
  change at runtime. Slice 5 (#16 tenancy) will promote this to
  a per-organization field.
- **`JournalEntry.of(ctx)` balances on base.** `Σ debit
  baseAmount = Σ credit baseAmount`. Transaction-currency
  balance is no longer required.
- **`JournalError.MixedCurrencies` is retained** on the sealed
  interface for sealed-switch exhaustiveness; it is no longer
  emitted by `of(...)`. The `ResultMapper` handler keeps its
  stable problem URI but the variant is now unreachable under
  current rules.
- **`JournalError.BaseCurrencyMismatch`** is added; fires when
  any posting's `baseAmount.currency()` ≠ `ctx.baseCurrency()`.
- **`JournalEntry.currency` field is removed** from the record.
  It carried a per-entry currency annotation that's meaningless
  post-Slice-6.
- **The client supplies both amounts.** No server-side FX rate
  lookup, no rate table, no revaluation in this slice. The
  client computes `baseMinorUnits` from whatever rate they used
  and sends both values in the request body.
- **Rounding convention: `RoundingMode.HALF_EVEN` (banker's
  rounding).** Server-side FX math isn't performed in Slice 6;
  this is the recommended convention for any client-side FX
  conversion *and* the default any future server-side FX work
  (Slice 7 revaluation) will use.
- **Same-currency-as-base postings** must still send
  `baseMinorUnits` (it equals `minorUnits` in that case).
  Explicit > implicit; uniform request shape.

## Consequences

- Cross-currency transactions become first-class.
- Reporting in base currency is straightforward — the
  `baseAmount` is always available on every posting.
- The single-currency invariant disappears from the domain;
  `MixedCurrencies` becomes dead-code-but-handled. Future
  cleanup may drop the variant.
- The wire format for `POST /journal-entries` is a breaking
  change: top-level `currency` is removed; per-posting
  `currency` and `baseMinorUnits` are required. The OpenAPI
  snapshot regen flags this; the Slice 6 Phase C PR carries the
  `breaking-change-approved` label.
- The Flyway V5 migration is non-trivial: currency moves from
  `journal_entries` to `postings`, and `postings.base_minor_units`
  is backfilled from `amount_minor_units` (pre-Slice-6 was
  single-USD, so they're equal).
- We accept the constraint of a single global base currency
  until Slice 5 tenancy lands.
- We accept that the client computes `baseMinorUnits`. Slice 7
  will let the server compute it from a stored FX rate table.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Flip the 0014 row to:

```
| [0014](0014-multi-currency-base-anchoring.md) | Multi-currency journal entries with base-currency anchoring | Accepted |
```

If 0014 isn't yet in the index, insert in numerical order between 0013 and the next entry.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0014-multi-currency-base-anchoring.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): 0014 multi-currency journal entries with base-currency anchoring

Drops the single-currency-per-entry rule. Each Posting carries
(amount, baseAmount). Entries balance in base currency, configured
globally via keystone.base-currency. New JournalError variant
BaseCurrencyMismatch; MixedCurrencies retained for sealed-switch
exhaustiveness but no longer emitted. JournalEntry.currency field
removed.

Client supplies both amounts; no server-side FX in this slice.
HALF_EVEN rounding convention. Wire format breaking change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `KeystoneProperties` config record

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/KeystoneProperties.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/config/KeystonePropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeystoneProperties")
class KeystonePropertiesTest {

  @Test
  @DisplayName("baseCurrency defaults to USD when null supplied")
  void shouldDefaultBaseCurrencyToUsdWhenNull() {
    KeystoneProperties p = new KeystoneProperties(null);
    assertEquals(Currency.getInstance("USD"), p.baseCurrency());
  }

  @Test
  @DisplayName("baseCurrency is whatever is supplied when non-null")
  void shouldUseSuppliedBaseCurrencyWhenNonNull() {
    KeystoneProperties p = new KeystoneProperties(Currency.getInstance("EUR"));
    assertEquals(Currency.getInstance("EUR"), p.baseCurrency());
  }
}
```

- [ ] **Step 2: Verify compile fails**

```bash
./mvnw -B test -Dtest=KeystonePropertiesTest 2>&1 | tail -10
```

Expected: `cannot find symbol class KeystoneProperties`.

- [ ] **Step 3: Implement `KeystoneProperties`**

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration root for keystone-wide application settings.
 *
 * <p>Bound to the {@code keystone} prefix in {@code application.yaml}:
 *
 * <pre>
 * keystone:
 *   base-currency: USD
 * </pre>
 *
 * <p>Defaults to USD when {@code base-currency} is absent or null.
 */
@ConfigurationProperties("keystone")
public record KeystoneProperties(Currency baseCurrency) {

  public KeystoneProperties {
    if (baseCurrency == null) {
      baseCurrency = Currency.getInstance("USD");
    }
  }
}
```

- [ ] **Step 4: Enable @ConfigurationProperties scanning**

Spring Boot auto-detects records annotated with `@ConfigurationProperties` when there's a `@ConfigurationPropertiesScan` somewhere. Check `KeystoneApplication.java` — if it doesn't already have `@ConfigurationPropertiesScan`, add it:

```java
@SpringBootApplication
@ConfigurationPropertiesScan  // add this if not present
public class KeystoneApplication { ... }
```

(Import: `org.springframework.boot.context.properties.ConfigurationPropertiesScan`.)

If the annotation is already there from a previous slice, skip this sub-step.

- [ ] **Step 5: Verify pass**

```bash
./mvnw -B test -Dtest=KeystonePropertiesTest 2>&1 | tail -10
```

Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(config): KeystoneProperties (@ConfigurationProperties) for base currency

Single-field record bound to the keystone prefix in application.yaml.
Defaults to USD when null. application.yaml gains the property in
Phase B.

@ConfigurationPropertiesScan added to KeystoneApplication if not
already present.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `JournalError.BaseCurrencyMismatch` + `ResultMapper` handler

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`

Sealed-switch exhaustiveness means the `ResultMapper.toProblemDetail(JournalError)` switch breaks the moment a new variant lands on the sealed interface. Both files change in the same commit so the build stays green.

- [ ] **Step 1: Add the variant**

In `JournalError.java`, after the existing variants, add:

```java
  /**
   * A posting's baseAmount currency differs from the configured base currency.
   *
   * @param code the offending account code
   * @param expectedByConfig the configured base from {@code keystone.base-currency}
   * @param actualOnPosting the currency the posting's baseAmount was sent with
   */
  record BaseCurrencyMismatch(
      co.embracejoy.accounting.keystone.domain.account.AccountCode code,
      java.util.Currency expectedByConfig,
      java.util.Currency actualOnPosting)
      implements JournalError {}
```

- [ ] **Step 2: Add the `ResultMapper` handler**

In `ResultMapper.toProblemDetail(JournalError)`, add to the sealed switch (alongside the other variants):

```java
      case JournalError.BaseCurrencyMismatch m -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/base-currency-mismatch",
          "Posting baseAmount currency does not match the configured base",
          "Account '"
              + m.code().value()
              + "' posting carries baseAmount in "
              + m.actualOnPosting().getCurrencyCode()
              + " but the configured base currency is "
              + m.expectedByConfig().getCurrencyCode()
              + ".");
```

- [ ] **Step 3: Verify compile + existing tests pass**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all existing tests pass; no new test yet (Phase B adds the `BaseCurrencyMismatch` validation path test in `JournalEntryTest`).

- [ ] **Step 4: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain,web): JournalError gains BaseCurrencyMismatch variant

New sealed variant fires when a posting's baseAmount.currency()
differs from the configured base. ResultMapper handler added in the
same commit so the sealed switch stays compiling; emits a stable
/journal/base-currency-mismatch type URI.

The MixedCurrencies variant is retained for sealed-switch
exhaustiveness but no longer emitted by JournalEntry.of(...) — see
ADR-0014.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `JournalValidationContext` — gains `baseCurrency`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContext.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContextTest.java`

Slice 3's `JournalValidationContext` has 4 fields: `(accounts, nonLeafCodes, periodStatus, permissiveMode)`. Slice 6 adds a 5th: `baseCurrency`. Existing back-compat constructors are updated to default `baseCurrency` to USD.

- [ ] **Step 1: Update the record**

Replace `JournalValidationContext.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry.
 *
 * <p>The application service does the I/O (account + period lookups, base currency from config)
 * and packs results here.
 */
public record JournalValidationContext(
    Map<AccountCode, Account> accounts,
    Set<AccountCode> nonLeafCodes,
    PeriodStatus periodStatus,
    Currency baseCurrency,
    boolean permissiveMode) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    Objects.requireNonNull(periodStatus, "periodStatus");
    Objects.requireNonNull(baseCurrency, "baseCurrency");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  /** Back-compat 4-arg constructor; defaults baseCurrency to USD. */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts,
      Set<AccountCode> nonLeafCodes,
      PeriodStatus periodStatus,
      boolean permissiveMode) {
    this(accounts, nonLeafCodes, periodStatus, Currency.getInstance("USD"), permissiveMode);
  }

  /** Back-compat 2-arg constructor; defaults to OPEN period, USD base, non-permissive. */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {
    this(accounts, nonLeafCodes, PeriodStatus.OPEN, Currency.getInstance("USD"), false);
  }

  /** Permissive context — skip both account and period checks. */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(
        Map.of(), Set.of(), PeriodStatus.OPEN, Currency.getInstance("USD"), true);
  }
}
```

- [ ] **Step 2: Update `JournalValidationContextTest`**

Add two new tests:

```java
  @Test
  @DisplayName("rejects null baseCurrency")
  void shouldThrowWhenBaseCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JournalValidationContext(
                Map.of(), Set.of(), PeriodStatus.OPEN, null, false));
  }

  @Test
  @DisplayName("4-arg back-compat constructor defaults baseCurrency to USD")
  void shouldDefaultBaseCurrencyToUsdWhen4ArgConstructorUsed() {
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(), Set.of(), PeriodStatus.OPEN, false);
    assertEquals(Currency.getInstance("USD"), ctx.baseCurrency());
  }
```

(Plus imports for `Currency`, `Map`, etc. if not already present.)

- [ ] **Step 3: Verify**

```bash
./mvnw -B test -Dtest=JournalValidationContextTest 2>&1 | tail -10
```

Expected: all tests pass; count grows by 2.

- [ ] **Step 4: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalValidationContext gains baseCurrency field

Slice-6 fifth field. The new 5-arg constructor is canonical; the
existing 4-arg and 2-arg back-compat constructors default
baseCurrency to USD so historical callers compile unchanged. The
permissive() factory passes through USD.

Phase B's JournalEntry.of() update uses ctx.baseCurrency() in the
new BaseCurrencyMismatch check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `Posting` record — add `baseAmount` field (BREAKING)

Posting's signature changes from 3-arg `(account, side, amount)` to 4-arg `(account, side, amount, baseAmount)`. Every call site updates in this commit.

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/Posting.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PostingTest.java`
- Modify: all other test files that construct `Posting` directly (search with grep)
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java` (mapper constructs `Posting` when reconstituting; Phase B will revise the mapper further)
- Modify: any application code that constructs `Posting` directly (probably none — usually built from the controller)

- [ ] **Step 1: Find every Posting construction site**

```bash
grep -rn "new Posting(" --include='*.java' src/ 2>&1 | head -30
```

Expect ~10-20 call sites across tests + the JPA mapper. Note them all before editing.

- [ ] **Step 2: Update `Posting.java`**

Replace `Posting.java` with:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Objects;

/**
 * A single debit or credit against an account, with transaction-currency and base-currency
 * amounts.
 *
 * <p>Sign is carried by {@link Side}; both {@link Money} amounts are non-negative. Zero is
 * allowed (memo postings). The {@code amount.currency()} is the transaction currency (must
 * match the account's currency, per Slice 2); {@code baseAmount.currency()} is the configured
 * base currency (validated in {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)}).
 */
public record Posting(AccountCode account, Side side, Money amount, Money baseAmount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(baseAmount, "baseAmount");
    if (amount.minorUnits() < 0L) {
      throw new IllegalArgumentException(
          "amount must be non-negative; sign is carried by Side");
    }
    if (baseAmount.minorUnits() < 0L) {
      throw new IllegalArgumentException("baseAmount must be non-negative");
    }
  }
}
```

- [ ] **Step 3: Update `PostingTest.java`**

Replace the existing tests (which use the 3-arg constructor) with versions that pass `baseAmount`. For pre-Slice-6 same-currency cases, `baseAmount == amount` is natural. Sample replacement for an existing test:

```java
  @Test
  @DisplayName("rejects null baseAmount")
  void shouldThrowWhenBaseAmountIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Posting(CASH, Side.DEBIT, new Money(100L, USD), null));
  }

  @Test
  @DisplayName("rejects negative baseAmount; sign carried by Side")
  void shouldThrowWhenBaseAmountIsNegative() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Posting(CASH, Side.DEBIT, new Money(100L, USD), new Money(-1L, USD)));
  }

  @Test
  @DisplayName("allows multi-currency: amount and baseAmount with different currencies")
  void shouldAcceptWhenAmountAndBaseAmountHaveDifferentCurrencies() {
    Posting p =
        new Posting(
            CASH, Side.DEBIT, new Money(9200L, EUR), new Money(10000L, USD));
    assertEquals(EUR, p.amount().currency());
    assertEquals(USD, p.baseAmount().currency());
  }
```

Update all existing PostingTest tests that use the old 3-arg constructor — pass `amount` as `baseAmount` for same-currency cases. The test count grows by ~3 net (new tests for baseAmount; existing tests just update).

- [ ] **Step 4: Update every other Posting construction site**

Search results from Step 1. For each:

- In `JournalEntryEntityMapper.toDomain(JournalEntryEntity)`: it currently does `new Posting(accountCode, side, money)`. Change to `new Posting(accountCode, side, money, money)` (since pre-Slice-6 data is single-USD; baseAmount == amount). Phase B will further update this to read `currency` and `base_minor_units` from the entity.

- In test files (`JournalEntryTest.java`, `PostJournalEntryServiceTest.java`, `JournalEntryControllerTest.java`, `ApplicationSmokeIT.java`, any others): for each `new Posting(account, side, amount)`, change to `new Posting(account, side, amount, amount)`. The test bodies don't care about a separate baseAmount yet (Phase B will introduce multi-currency-specific tests).

Helper pattern in test files (if they have `debit(account, amount)` and `credit(account, amount)` helpers): update the helpers to construct 4-arg `Posting` with `baseAmount = amount`. New helpers `debitWithBase(account, txAmount, baseAmount)` and `creditWithBase(...)` can be added in Phase B when needed.

- [ ] **Step 5: Verify**

```bash
./mvnw -B test 2>&1 | tail -15
```

Expected: BUILD SUCCESS. Total test count: existing + 3 new (for baseAmount validation in `PostingTest`).

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Posting record gains baseAmount field (4-arg)

BREAKING type change: Posting now carries Money baseAmount alongside
the transaction-currency amount. Constructor checks both are
non-negative.

All existing call sites updated in the same commit: pre-Slice-6
single-USD data sets baseAmount = amount (identity). Tests passing
through helpers (debit/credit/...) are updated to construct the
4-arg form. The JPA mapper now constructs Posting with both
arguments; Phase B will further update it to read currency and
base_minor_units from the entity row.

Three new PostingTest cases: null/negative baseAmount, multi-currency
construction with different currencies on amount and baseAmount.

Captured in ADR-0014.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `JournalEntry` record — drop `currency` field (BREAKING)

The record's `currency` field is meaningless post-Slice-6 (postings carry their own currency). Drop it; update all callers.

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java` (drop top-level `currency` field — Phase C will further extend the DTO)
- Modify: any other call sites (search with grep)

- [ ] **Step 1: Find every JournalEntry construction site**

```bash
grep -rn "new JournalEntry(" --include='*.java' src/ 2>&1 | head -20
```

- [ ] **Step 2: Update `JournalEntry.java`**

Replace the record header from:

```java
public record JournalEntry(
        LocalDate occurredOn, String description, Currency currency, List<Posting> postings) { ... }
```

to:

```java
public record JournalEntry(
        LocalDate occurredOn, String description, List<Posting> postings) { ... }
```

Update the canonical constructor (drop the `currency` null-check and field assignment). Update the static factory `of(...)` — Phase B will rewrite it fully, but for Task 6 just drop the `currency` parameter from any internal construction (e.g., the current `of()` likely computes a single currency from postings and passes it to the canonical constructor; remove that step now since Phase B's of() doesn't compute it anymore).

Trim the `of(...)` to its Phase-A interim form — same validation as today minus the `MixedCurrencies` check, and construct via the new 3-arg canonical:

```java
  public static Result<JournalEntry, JournalError> of(
      LocalDate occurredOn,
      String description,
      List<Posting> postings,
      JournalValidationContext ctx) {
    Objects.requireNonNull(occurredOn, "occurredOn");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(postings, "postings");
    Objects.requireNonNull(ctx, "ctx");

    if (postings.isEmpty()) {
      return Result.failure(new JournalError.NoPostings());
    }

    // Phase A interim: no MixedCurrencies check anymore. Phase B will add
    // the BaseCurrencyMismatch check and revise the balance-on-base check.

    if (!ctx.permissiveMode() && ctx.periodStatus() == PeriodStatus.CLOSED) {
      return Result.failure(
          new JournalError.PostingInClosedPeriod(java.time.YearMonth.from(occurredOn)));
    }

    // Per-posting account checks (unchanged from Slice 2)
    for (Posting p : postings) {
      AccountCode code = p.account();
      Account account = ctx.accounts().get(code);
      if (account == null) {
        if (ctx.permissiveMode()) continue;
        return Result.failure(new JournalError.AccountNotFound(code));
      }
      if (!account.active()) return Result.failure(new JournalError.AccountInactive(code));
      if (ctx.nonLeafCodes().contains(code)) {
        return Result.failure(new JournalError.AccountNotALeaf(code));
      }
      if (!account.currency().equals(p.amount().currency())) {
        return Result.failure(
            new JournalError.AccountCurrencyMismatch(
                code, account.currency(), p.amount().currency()));
      }
    }

    // Phase A interim balance check: balance on baseAmount per the spec, but
    // without yet validating BaseCurrencyMismatch (that comes in Phase B's
    // dedicated validation-order task).
    Money zero = new Money(0L, ctx.baseCurrency());
    return sum(postings, Side.DEBIT, zero)
        .flatMap(
            debits ->
                sum(postings, Side.CREDIT, zero)
                    .flatMap(
                        credits -> {
                          if (debits.minorUnits() != credits.minorUnits()) {
                            return Result.failure(new JournalError.Unbalanced(debits, credits));
                          }
                          return Result.success(
                              new JournalEntry(occurredOn, description, postings));
                        }));
  }

  // sum() now sums baseAmount (not amount); the zero is in base currency.
  private static Result<Money, JournalError> sum(
      List<Posting> postings, Side side, Money zero) {
    Money acc = zero;
    for (Posting p : postings) {
      if (p.side() == side) {
        Result<Money, MoneyError> next = acc.plus(p.baseAmount());
        if (next instanceof Result.Success<Money, MoneyError> s) {
          acc = s.value();
        } else {
          return Result.failure(new JournalError.Overflow(side));
        }
      }
    }
    return Result.success(acc);
  }
```

(This Phase-A version of `of()` is a complete-enough interim — Phase B's Task 9 will add the `BaseCurrencyMismatch` check between account validation and balance. For Task 6 commit, the validation is "interim correct" — every existing test still passes because Slice 2/3 tests used same-currency entries where baseAmount == amount.)

Drop existing `JournalEntry.currency()` accessor calls from other code (the record's auto-generated accessor goes away with the field). Search:

```bash
grep -rn "\.currency()" --include='*.java' src/ 2>&1 | grep -v "money\|Money\|account\|Account\|periodStatus\|baseCurrency" | head -10
```

Anything that called `entry.currency()` needs to be updated. The DTO `JournalEntryResponse` is the most obvious — drop the field there too (Phase C will further redo the DTOs).

- [ ] **Step 3: Update test fixtures**

Some test fixtures may have assertions like `assertEquals(USD, entry.currency())`. Remove those. Any test that needs to know the currency of postings can call `postings.get(0).amount().currency()`.

- [ ] **Step 4: Update `JournalEntryEntityMapper.toDomain`**

Currently it reads `entity.getCurrency()` and passes it to `new JournalEntry(...)`. Drop both — just `new JournalEntry(occurredOn, description, postings)`. Note: `entity.getCurrency()` is the old column; Phase B's V5 migration drops it AND the entity field — but Task 6 still has the entity field present. Read it but discard (don't pass it forward).

- [ ] **Step 5: Update `JournalEntryResponse` DTO**

Drop the top-level `currency` field from the response record. The wire shape becomes:

```java
public record JournalEntryResponse(
    String id,
    LocalDate occurredOn,
    String description,
    List<PostingResponse> postings) { ... }
```

(Phase C will further extend `PostingResponse` to include per-posting currency + baseMinorUnits. Task 6 just removes the top-level field.)

- [ ] **Step 6: Verify**

```bash
./mvnw -B test 2>&1 | tail -15
```

Expected: BUILD SUCCESS. Some test expectations may need adjustment around currency-tracking; address them in the same commit.

- [ ] **Step 7: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntry record drops currency field

BREAKING type change: JournalEntry no longer carries a top-level
currency. Postings carry their own currency post-Slice-6. The
canonical 3-arg constructor (occurredOn, description, postings)
replaces the 4-arg form. JournalEntry.of() now balances on
baseAmount in ctx.baseCurrency() (interim — Phase B's Task 9 adds
the BaseCurrencyMismatch check inline).

Call sites updated:
- JournalEntryEntityMapper drops the entity.getCurrency() pass-through
  (entity still has the column; Phase B's V5 migration removes it).
- JournalEntryResponse DTO drops the top-level currency field (Phase
  C further extends PostingResponse).
- Tests update their assertions; currency assertions move from entry
  to posting.

Captured in ADR-0014.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Phase A acceptance — final verify

**Files:** none

- [ ] **Step 1: Cold-cache verify**

```bash
./mvnw -B clean test 2>&1 | tail -15
```

Expected: BUILD SUCCESS. Total test count is roughly the same as pre-Phase-A — added 2 KeystonePropertiesTest + 2 JournalValidationContextTest + 3 PostingTest = +7 net, no tests removed.

- [ ] **Step 2: No commit; Phase A is done**

Push the branch and open the PR. PR title: `Slice 6 Phase A: multi-currency domain type changes + ADR-0014`.

---

## Phase A acceptance

7 commits (1-6 plus the implicit plan-doc commit at the start). All gates green. `Posting` is 4-arg with `baseAmount`; `JournalEntry` is 3-arg without `currency`; `JournalValidationContext` carries `baseCurrency`; `BaseCurrencyMismatch` is a sealed variant with a `ResultMapper` handler; `KeystoneProperties` is bound to `keystone.*`.

**Bootstrap note:** the plan document `2026-05-13-slice-6-multi-currency.md` is the first commit on this branch (already added before Task 1 starts):

```bash
git add docs/superpowers/plans/2026-05-13-slice-6-multi-currency.md
git commit -m "$(cat <<'EOF'
docs(plan): Slice 6 — multi-currency journal entries (Phases A-C)

Three phases, three PRs. Phase A makes the domain type changes
(Posting gains baseAmount, JournalEntry drops currency,
JournalValidationContext gains baseCurrency, BaseCurrencyMismatch
JournalError variant added, KeystoneProperties added). Phase B
rewrites JournalEntry.of() validation, lands Flyway V5, updates JPA
entities/mapper/IT, wires KeystoneProperties into the service.
Phase C ships the breaking wire-format change, regenerates the
OpenAPI snapshot under the breaking-change-approved label, and
extends the smoke IT.

Spec authority:
docs/superpowers/specs/2026-05-13-slices-4-6-multi-currency-and-trial-balance-design.md.
On merge of Phase C, issue #17 closes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase B — Validation rewrite + persistence + service wiring

Phase B finishes the validation pipeline (full `BaseCurrencyMismatch` check + base-balance check), lands the Flyway V5 migration with its full SQL choreography, updates the JPA entity layer + IT, and wires `KeystoneProperties` into `PostJournalEntryService` via `ApplicationConfig`.

Phase B produces ~8 commits.

---

## Task 8: Add `BaseCurrencyMismatch` check inside `JournalEntry.of(...)`

Phase A's Task 6 left an "interim" version of `of()` that balances on baseAmount but doesn't yet check that every posting's baseAmount currency matches the configured base. Task 8 adds that check.

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`

- [ ] **Step 1: Add the failing test**

Append to `JournalEntryTest.java`:

```java
  @Test
  @DisplayName("of(ctx) returns Failure(BaseCurrencyMismatch) when posting baseAmount currency differs")
  void shouldReturnBaseCurrencyMismatchWhenBaseCurrencyDiffers() {
    Account cash =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity =
        new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cash, EQUITY, equity),
            Set.of(),
            PeriodStatus.OPEN,
            Currency.getInstance("USD"),
            false);
    // Posting carries baseAmount in EUR but the configured base is USD.
    Posting bad =
        new Posting(CASH, Side.DEBIT, new Money(100L, USD), new Money(92L, EUR));
    Posting good =
        new Posting(EQUITY, Side.CREDIT, new Money(100L, USD), new Money(100L, USD));

    Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "x", List.of(bad, good), ctx);

    JournalError.BaseCurrencyMismatch e =
        (JournalError.BaseCurrencyMismatch)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
    assertEquals(Currency.getInstance("USD"), e.expectedByConfig());
    assertEquals(EUR, e.actualOnPosting());
  }

  @Test
  @DisplayName("of(ctx) returns Success for a multi-currency entry that balances in base")
  void shouldReturnSuccessWhenMultiCurrencyBalancesInBase() {
    Account cashUsd =
        new Account(CASH, "Cash USD", AccountType.ASSET, USD, Optional.empty(), true);
    AccountCode CASH_EUR_CODE = new AccountCode("1000-EUR");
    Account cashEur =
        new Account(CASH_EUR_CODE, "Cash EUR", AccountType.ASSET, EUR, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cashUsd, CASH_EUR_CODE, cashEur),
            Set.of(),
            PeriodStatus.OPEN,
            USD,
            false);
    // USD → EUR transfer at 0.92 rate: 100 USD = 92 EUR.
    // baseAmount on both is the USD-equivalent.
    Posting debitEur =
        new Posting(CASH_EUR_CODE, Side.DEBIT, new Money(9200L, EUR), new Money(10000L, USD));
    Posting creditUsd =
        new Posting(CASH, Side.CREDIT, new Money(10000L, USD), new Money(10000L, USD));

    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "USD→EUR transfer", List.of(debitEur, creditUsd), ctx);

    assertInstanceOf(Result.Success.class, r);
  }
```

(plus imports for `Currency`, `EUR` constant if not already present).

- [ ] **Step 2: Verify the test fails as expected**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -15
```

Expected: the new BaseCurrencyMismatch test FAILS (current `of()` doesn't check) — it likely returns Unbalanced because debits/credits don't sum to the same value in USD when EUR is supplied as baseAmount.

- [ ] **Step 3: Add the BaseCurrencyMismatch check in `of(...)`**

In `JournalEntry.of(...)`, between the account checks and the balance check, insert:

```java
    // BaseCurrencyMismatch check (Phase B): every posting's baseAmount must be in
    // the configured base currency.
    if (!ctx.permissiveMode()) {
      for (Posting p : postings) {
        if (!p.baseAmount().currency().equals(ctx.baseCurrency())) {
          return Result.failure(
              new JournalError.BaseCurrencyMismatch(
                  p.account(), ctx.baseCurrency(), p.baseAmount().currency()));
        }
      }
    }
```

The permissive-mode skip mirrors the account-check skip — historical 3-arg `of()` callers use permissive contexts and the base-currency check would always trip.

- [ ] **Step 4: Verify all tests pass**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -10
```

Expected: all green. Count grew by 2.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntry.of(ctx) rejects baseAmount currency mismatch

New check between account validation and base-balance check:
per-posting baseAmount.currency() must equal ctx.baseCurrency().
Failing returns JournalError.BaseCurrencyMismatch with the offending
account code + expected + actual.

Two new TDD tests: the mismatch case and a multi-currency-but-balances
success case (USD→EUR transfer with USD as base; baseAmount equals
USD on both postings).

Permissive contexts (3-arg of() callers from historical tests) skip
this check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Flyway V5 migration

**Files:**
- Create: `src/main/resources/db/migration/V5__postings_multi_currency.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V5__postings_multi_currency.sql
-- Slice 6: per-posting currency + base-currency amount.

-- 1. Add currency column to postings (will hold the transaction currency).
ALTER TABLE postings ADD COLUMN currency CHAR(3);

-- 2. Backfill from the entry's currency.
UPDATE postings p
   SET currency = (SELECT currency FROM journal_entries WHERE id = p.journal_entry_id);

-- 3. Tighten to NOT NULL.
ALTER TABLE postings ALTER COLUMN currency SET NOT NULL;

-- 4. Add base_minor_units column.
ALTER TABLE postings ADD COLUMN base_minor_units BIGINT;

-- 5. Backfill: pre-Slice-6 was single-currency-USD, so base equals amount.
UPDATE postings SET base_minor_units = amount_minor_units;

-- 6. Tighten + non-negative check.
ALTER TABLE postings ALTER COLUMN base_minor_units SET NOT NULL;
ALTER TABLE postings
    ADD CONSTRAINT postings_base_minor_units_nonneg
    CHECK (base_minor_units >= 0);

-- 7. Drop the (now redundant) entry-level currency.
ALTER TABLE journal_entries DROP COLUMN currency;
```

- [ ] **Step 2: Verify Flyway runs cleanly**

The Testcontainers ITs run V5 automatically. Run:

```bash
./mvnw -B verify 2>&1 | tail -20
```

Expected: BUILD SUCCESS. If V5 has a syntax error or breaks against the seed data, the IT fails — adjust V5 and re-run.

Note: existing JournalEntryEntity references `currency` as a column; after V5 drops it, the entity validation (`hibernate.ddl-auto: validate`) will fail. **Task 10 immediately follows** with the entity change so that compile + IT stay green. Task 9 and 10 land together in a single commit to keep the migration + entity in lockstep.

Actually — let's bundle: do Task 9 AND Task 10's entity changes in one commit. Slate them as Task 9-10 below.

- [ ] **Step 3: Update `JournalEntryEntity` to drop `currency`**

(This is the Task 10 content; bundled with Task 9 to keep the build green across the V5 migration.)

In `JournalEntryEntity.java`, drop the `currency` field, getter, and setter. Drop the constructor parameter. Update any internal usage.

- [ ] **Step 4: Update `PostingEntity` to add `currency` + `baseMinorUnits`**

In `PostingEntity.java`, add:

```java
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "base_minor_units", nullable = false)
  private long baseMinorUnits;
```

Plus getters and a 4-arg constructor (or update the existing constructor to take all fields). Mark old setters/getters appropriately.

- [ ] **Step 5: Update `JournalEntryEntityMapper`**

- `toEntity(JournalEntry, UUID)`: per-posting, set `currency` and `baseMinorUnits` on the entity from `posting.amount().currency()` and `posting.baseAmount().minorUnits()`.
- `toDomain(JournalEntryEntity)`: read each `PostingEntity`'s `currency` (as `Currency.getInstance(s)`) and `baseMinorUnits`. Construct `Posting` with both `amount` and `baseAmount` (`baseAmount` uses the entry's base currency — but wait, the *entity* doesn't carry base currency anywhere; only the configured app-level base does). The mapper reconstitutes data; it doesn't have access to `KeystoneProperties`.

   Resolution: store the base currency *per posting row* too — wait, the spec says it's the same base for all postings (configured globally). So `baseAmount.currency()` is implicit: read from a global config or just assume USD.

   Two options:
   - (a) Add `base_currency` column to postings — wasteful (always the same value).
   - (b) Add `base_currency` to journal_entries — wasteful too.
   - (c) Mapper takes a `Currency baseCurrency` parameter, supplied by the caller (the adapter, which has access to `KeystoneProperties`).
   - (d) Hardcode USD in the mapper; document the assumption.

   Best: **(c)**. The mapper signature becomes `toDomain(JournalEntryEntity entity, Currency baseCurrency)`. The `JpaJournalEntryRepository` adapter holds a `Currency baseCurrency` field (injected) and passes it to every `toDomain` call. The mapper stays domain-agnostic of configuration.

   Update:
   - `JournalEntryEntityMapper.toDomain(JournalEntryEntity)` → `toDomain(JournalEntryEntity, Currency baseCurrency)`.
   - Same for any other mapper methods that reconstruct.
   - `JpaJournalEntryRepository` (the adapter): constructor now takes `Currency baseCurrency`; passes it to mapper calls.
   - `JpaJournalEntryRepository` Spring wiring: gets baseCurrency from `KeystoneProperties` via `ApplicationConfig`.

- [ ] **Step 6: Verify**

```bash
./mvnw -B verify 2>&1 | tail -20
```

Expected: BUILD SUCCESS. The existing `JpaJournalEntryRepositoryIT` round-trip test passes because the seed Posting values had `currency = USD` and `baseMinorUnits = amount_minor_units` after V5.

- [ ] **Step 7: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/main/resources/db/migration/V5__postings_multi_currency.sql src/
git commit -m "$(cat <<'EOF'
feat(persistence): Flyway V5 + JPA entity updates for multi-currency

V5 migration moves currency from journal_entries to postings,
backfills postings.base_minor_units from amount_minor_units (pre-
Slice-6 single-USD data is trivially in base), and drops
journal_entries.currency.

JournalEntryEntity drops the currency field. PostingEntity gains
currency (CHAR(3)) and baseMinorUnits (BIGINT) columns + getters.
JournalEntryEntityMapper.toDomain takes a Currency baseCurrency
parameter so the mapper can reconstitute Posting with both amount
and baseAmount; the adapter holds the configured base and passes it
through. JpaJournalEntryRepository constructor now takes baseCurrency
(wired in Phase B Task 11 from KeystoneProperties).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10 (consolidated above with Task 9)

Already done in Task 9 — entity + mapper updates are bundled into the V5 migration commit because they must land together for the schema-vs-entity validation to stay green.

---

## Task 11: Wire `KeystoneProperties` into `PostJournalEntryService` + `ApplicationConfig`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Update `PostJournalEntryService`**

```java
public final class PostJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final AccountRepository accountRepository;
  private final PeriodService periodService;
  private final Currency baseCurrency;

  public PostJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService,
      Currency baseCurrency) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
    this.periodService = Objects.requireNonNull(periodService, "periodService");
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
  }

  public Result<PersistedJournalEntry, JournalError> post(
      LocalDate occurredOn, String description, List<Posting> postings) {
    Set<AccountCode> codes =
        postings.stream().map(Posting::account).collect(Collectors.toCollection(HashSet::new));
    Map<AccountCode, Account> accounts = accountRepository.findByCodeIn(codes);
    Set<AccountCode> nonLeafCodes =
        accounts.keySet().stream()
            .filter(accountRepository::hasChildren)
            .collect(Collectors.toUnmodifiableSet());
    PeriodStatus periodStatus =
        periodService.findByYearMonth(YearMonth.from(occurredOn)).status();
    JournalValidationContext ctx =
        new JournalValidationContext(
            accounts, nonLeafCodes, periodStatus, baseCurrency, false);
    return JournalEntry.of(occurredOn, description, postings, ctx).map(journalRepository::save);
  }
}
```

- [ ] **Step 2: Update `PostJournalEntryServiceTest`**

The service constructor gains a 4th arg. Update all `new PostJournalEntryService(...)` calls to pass `Currency.getInstance("USD")`. The fakes don't need to change.

- [ ] **Step 3: Update `ApplicationConfig`**

```java
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
  public Currency keystoneBaseCurrency(KeystoneProperties properties) {
    return properties.baseCurrency();
  }
```

The `keystoneBaseCurrency` `@Bean` is the same `Currency` exposed as a bean so the `JpaJournalEntryRepository` adapter (added separately if not present already) can inject it.

Update `JpaJournalEntryRepository` (the adapter — find the actual class name on disk; might be `JournalEntryRepositoryAdapter` or `JpaJournalEntryRepository` per Plan 2 conventions): constructor adds `@Qualifier("keystoneBaseCurrency") Currency baseCurrency` or just `Currency baseCurrency` (Spring autowires by type since there's only one Currency bean). Store it; pass to mapper.

- [ ] **Step 4: Update `application.yaml`**

Add to the existing config:

```yaml
keystone:
  base-currency: USD
```

- [ ] **Step 5: Verify**

```bash
./mvnw -B verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/ docs/
git commit -m "$(cat <<'EOF'
feat(application): wire KeystoneProperties base currency into PostJournalEntryService

PostJournalEntryService constructor adds a Currency baseCurrency arg;
the service packs it into every JournalValidationContext via the
5-arg constructor. ApplicationConfig wires KeystoneProperties.baseCurrency()
to both the service and a standalone bean (used by the JPA adapter's
mapper for reconstituting baseAmount.currency()).

application.yaml gains `keystone.base-currency: USD` (env override
KEYSTONE_BASE_CURRENCY supported).

PostJournalEntryServiceTest constructor calls updated to pass
Currency.getInstance("USD").

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Extend `JpaJournalEntryRepositoryIT` with multi-currency round-trip

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepositoryIT.java`

- [ ] **Step 1: Add a new IT test**

Append one test that persists a multi-currency entry (USD→EUR transfer) and reads it back, asserting both postings preserve `currency` and `base_minor_units`. The existing seed CoA needs `1000-EUR` — either seed it via the test, or the V5 migration's seed could include it (but that's a Phase B add, not great).

Cleanest: the test creates the EUR account via the in-test `AccountRepositoryAdapter` first, then posts the multi-currency entry. Same pattern as ApplicationSmokeIT's account-create.

Pseudo-code (full code follows the existing IT pattern):

```java
  @Test
  @DisplayName("save+findById round-trips a multi-currency entry preserving currency and baseAmount")
  void shouldRoundTripMultiCurrencyEntry() {
    // Create an EUR cash account.
    accountRepository.save(new Account(
        new AccountCode("1000-EUR"), "Cash EUR", AccountType.ASSET,
        Currency.getInstance("EUR"), Optional.empty(), true));

    // USD→EUR entry: debit 9200 EUR (≡ $100), credit 10000 USD ($100).
    Posting debit = new Posting(
        new AccountCode("1000-EUR"), Side.DEBIT,
        new Money(9200L, Currency.getInstance("EUR")),
        new Money(10000L, Currency.getInstance("USD")));
    Posting credit = new Posting(
        CASH, Side.CREDIT,
        new Money(10000L, USD),
        new Money(10000L, USD));

    JournalEntry entry = /* construct via the canonical constructor — bypassing of() */
        new JournalEntry(LocalDate.of(2026, 5, 13), "USD→EUR transfer", List.of(debit, credit));

    PersistedJournalEntry saved = repository.save(entry);
    PersistedJournalEntry found = repository.findById(saved.id()).orElseThrow();

    assertThat(found.entry().postings()).hasSize(2);
    Posting debitOut = found.entry().postings().stream()
        .filter(p -> p.side() == Side.DEBIT).findFirst().orElseThrow();
    assertThat(debitOut.amount().currency()).isEqualTo(Currency.getInstance("EUR"));
    assertThat(debitOut.amount().minorUnits()).isEqualTo(9200L);
    assertThat(debitOut.baseAmount().currency()).isEqualTo(USD);
    assertThat(debitOut.baseAmount().minorUnits()).isEqualTo(10000L);
  }
```

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B verify 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(persistence): JpaJournalEntryRepositoryIT covers multi-currency round-trip

New IT persists a USD→EUR transfer entry (9200 EUR debit, 10000 USD
credit; baseAmount is USD on both) and asserts the round-trip
preserves per-posting currency + base_minor_units. Exercises the
V5-migrated schema end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Phase B acceptance — full verify

**Files:** none

- [ ] **Step 1: Cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate -Dopenapi.diff.skip=true 2>&1 | tail -30
```

`-Dopenapi.diff.skip=true` because the wire format hasn't changed yet (DTOs still take old shape; Phase C does that). The openapi-diff Layer 4 would otherwise no-op against current main, which is fine.

Expected: BUILD SUCCESS. JaCoCo + PIT thresholds hold.

- [ ] **Step 2: No commit; Phase B is done**

Push the branch `17-slice-6-phase-b-wiring` and open the PR.

---

## Phase B acceptance

~6 commits (Tasks 8, 9/10 bundled, 11, 12, plus the verify check). All gates green. `JournalEntry.of(ctx)` validates BaseCurrencyMismatch + balances on base. V5 migration in place. JPA entity + mapper handle per-posting currency + baseAmount. `PostJournalEntryService` reads base currency from `KeystoneProperties`.

---

# Phase C — Web layer + smoke + close #17

Phase C ships the breaking wire-format change, updates DTOs + controller + smoke, regenerates the OpenAPI snapshot, and closes #17.

Phase C produces ~6 commits.

---

## Task 14: Update DTOs for the new wire format

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostJournalEntryRequest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostingRequest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java` (already done in Phase A Task 6 — drop top-level currency)
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostingResponse.java`

- [ ] **Step 1: Drop top-level `currency` from `PostJournalEntryRequest`**

```java
public record PostJournalEntryRequest(
    @NotNull LocalDate occurredOn,
    @NotBlank @Size(max = 500) String description,
    @NotEmpty @Valid List<PostingRequest> postings) { ... }
```

(Drop the `currency` field; bean validation annotations on the remaining fields stay.)

- [ ] **Step 2: Add `currency` + `baseMinorUnits` to `PostingRequest`**

```java
public record PostingRequest(
    @NotBlank String account,
    @NotBlank @Pattern(regexp = "^(DEBIT|CREDIT)$") String side,
    @PositiveOrZero long minorUnits,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @PositiveOrZero long baseMinorUnits) { ... }
```

- [ ] **Step 3: Add `currency` + `baseMinorUnits` to `PostingResponse`**

```java
public record PostingResponse(
    String account, String side, long minorUnits, String currency, long baseMinorUnits) {

  public static PostingResponse of(Posting p) {
    return new PostingResponse(
        p.account().value(),
        p.side().name(),
        p.amount().minorUnits(),
        p.amount().currency().getCurrencyCode(),
        p.baseAmount().minorUnits());
  }
}
```

- [ ] **Step 4: Verify compile + existing tests**

```bash
./mvnw -B test 2>&1 | tail -10
```

Some `JournalEntryControllerTest` MockMvc tests reference the old wire shape — Task 15 updates them. Expect compile failures or test failures here; that's the next task's job.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): DTOs updated for multi-currency wire format

PostJournalEntryRequest drops top-level currency. PostingRequest
gains currency (Pattern ^[A-Z]{3}$) and baseMinorUnits (PositiveOrZero).
PostingResponse gains currency + baseMinorUnits (in PostingResponse.of()
unwrapped from the Posting record).

This commit may temporarily break JournalEntryControllerTest — Task
15 immediately fixes those tests for the new wire shape.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Update `JournalEntryController` + `JournalEntryControllerTest`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

- [ ] **Step 1: Update the controller**

The controller currently constructs `Posting` from `PostingRequest` using the request's `account`, `side`, `minorUnits` + a top-level `currency`. After Phase C's DTO update, currency moves to per-posting and baseMinorUnits is supplied. Update the mapping:

```java
  @PostMapping
  public ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request) {
    List<Posting> postings =
        request.postings().stream()
            .map(p -> {
              Currency txCurrency = Currency.getInstance(p.currency());
              // baseAmount.currency() is the configured base; the service will validate.
              // We don't know it here, so construct with the txCurrency as a placeholder
              // and let the service swap... actually, the controller has to know base.
              // Inject Currency baseCurrency into the controller via constructor.
              return new Posting(
                  new AccountCode(p.account()),
                  Side.valueOf(p.side()),
                  new Money(p.minorUnits(), txCurrency),
                  new Money(p.baseMinorUnits(), baseCurrency));
            })
            .toList();
    // ... rest unchanged: call service.post(...), fold Result
  }
```

The controller needs the base currency to construct `baseAmount`. Inject via constructor:

```java
  private final PostJournalEntryService service;
  private final Currency baseCurrency;
  private final Counter postedOk;
  // ... etc.

  public JournalEntryController(
      PostJournalEntryService service,
      Currency keystoneBaseCurrency,   // wired from ApplicationConfig
      ...) { ... }
```

(Spring autowires by type; if there's only one `Currency` bean, the parameter name doesn't matter. If you want explicit, use `@Qualifier("keystoneBaseCurrency")`.)

- [ ] **Step 2: Update `JournalEntryControllerTest`**

Existing tests construct request bodies like:

```json
{ "occurredOn": "...", "description": "...", "currency": "USD", "postings": [...] }
```

Update to:

```json
{ "occurredOn": "...", "description": "...",
  "postings": [
    { "account": "1000", "side": "DEBIT", "minorUnits": 1000, "currency": "USD", "baseMinorUnits": 1000 },
    ...
  ] }
```

Add a `@MockitoBean Currency keystoneBaseCurrency` field — Spring's mock substitutes it; in tests it's USD (`@TestPropertySource` or manual mock).

Actually simpler: don't mock the Currency bean. Use a real `Currency.getInstance("USD")` instance, registered as a `@TestConfiguration` bean. Or annotate the test with `@Import(TestConfig.class)` that provides one.

Cleanest: extend the `@TestConfiguration` (or add one) that provides `Currency keystoneBaseCurrency() { return Currency.getInstance("USD"); }`.

Plus add a new test for the `BaseCurrencyMismatch` failure path: mock `PostJournalEntryService.post(...)` to return `Result.failure(new JournalError.BaseCurrencyMismatch(...))`; assert 400 + `/problems/journal/base-currency-mismatch`.

- [ ] **Step 3: Run + commit**

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): JournalEntryController + tests handle multi-currency wire format

Controller constructor adds Currency keystoneBaseCurrency. Per
posting, constructs Money amount in the request's transaction
currency and Money baseAmount using the wired base currency +
the request's baseMinorUnits.

JournalEntryControllerTest updated for the new wire shape (no
top-level currency; per-posting currency + baseMinorUnits). One
new test for the BaseCurrencyMismatch failure path. Test config
provides a USD Currency bean.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: Extend `ApplicationSmokeIT` with multi-currency

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

- [ ] **Step 1: Add a new test**

Append a smoke test that:

1. Creates a `1000-EUR` Cash EUR account via `POST /accounts`.
2. Posts a USD→EUR transfer entry (debit `1000-EUR` 9200 EUR with baseAmount 10000 USD; credit `1000` 10000 USD with baseAmount 10000 USD).
3. Asserts 201 Created + the response includes per-posting `currency` + `baseMinorUnits`.

Keep methods under Checkstyle's 30-line limit — split into helpers if needed.

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B verify 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(smoke): ApplicationSmokeIT exercises multi-currency entry

Creates a 1000-EUR Cash EUR account, posts a USD→EUR transfer
(9200 EUR debit, 10000 USD credit; baseAmount in USD on both),
asserts 201 + response carries per-posting currency + baseMinorUnits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: Extend `ResultMapperTest` for `BaseCurrencyMismatch`

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapperTest.java`

Phase A Task 3 added the handler; Task 17 adds the test that exercises it.

- [ ] **Step 1: Add the test**

```java
  @Test
  @DisplayName("BaseCurrencyMismatch maps to 400 with stable type URI and detail")
  void shouldMapBaseCurrencyMismatchToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new JournalError.BaseCurrencyMismatch(
                new AccountCode("1000-EUR"),
                Currency.getInstance("USD"),
                Currency.getInstance("EUR")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/base-currency-mismatch");
    assertThat(pd.getDetail()).contains("1000-EUR").contains("USD").contains("EUR");
  }
```

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B test -Dtest=ResultMapperTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(web): ResultMapperTest covers BaseCurrencyMismatch handler

One new test asserts status 400, type URI ends with
/journal/base-currency-mismatch, and detail contains the offending
account code + expected + actual currencies.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: Regenerate OpenAPI snapshot + README + CLAUDE.md + final verify

**Files:**
- Modify: `docs/openapi/openapi.yaml`
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Regenerate the OpenAPI snapshot**

Start local Postgres if needed:

```bash
docker run -d --name kp-pg -p 5434:5432 \
  -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
  -e POSTGRES_DB=keystone postgres:16
sleep 6
./mvnw -B verify -Popenapi-update -Dopenapi.diff.skip=true 2>&1 | tail -10
docker rm -f kp-pg
```

The committed `docs/openapi/openapi.yaml` now reflects the new wire format: top-level `currency` is gone from `PostJournalEntryRequest`; `PostingRequest` and `PostingResponse` carry `currency` and `baseMinorUnits`. `JournalEntryResponse` drops top-level `currency`.

Confirm the regenerated file has the changes you expect (open it; verify the `currency` field is no longer under `PostJournalEntryRequest`'s required list, and that `PostingRequest`'s schema has the two new properties).

- [ ] **Step 2: Update `README.md`**

Flip Slice 6 status to ✅ (add the row if it doesn't exist):

```markdown
- [x] Slice 6 — multi-currency journal entries (#17)
```

Update the Quick Start request body example to reflect the new wire shape:

```bash
curl -i -X POST http://localhost:8080/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "occurredOn": "2026-05-13",
    "description": "opening balance",
    "postings": [
      { "account": "1000", "side": "DEBIT",  "minorUnits": 10000,
        "currency": "USD", "baseMinorUnits": 10000 },
      { "account": "3000", "side": "CREDIT", "minorUnits": 10000,
        "currency": "USD", "baseMinorUnits": 10000 }
    ]
  }'
```

- [ ] **Step 3: Update `CLAUDE.md` Key Conventions**

Add a bullet for multi-currency:

```markdown
- **Multi-currency is base-anchored.** Each `Posting` carries `(amount, baseAmount)`. The `amount.currency()` is the transaction currency; `baseAmount.currency()` must equal the configured `keystone.base-currency` (default USD). `JournalEntry.of(...)` balances on `baseAmount`. See [ADR-0014](docs/adr/0014-multi-currency-base-anchoring.md).
```

- [ ] **Step 4: Final cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate -Dopenapi.diff.skip=true 2>&1 | tail -30
```

Skip the breaking-change diff (Layer 4) — the new snapshot is intentionally different from main's. CI will run with `-Dopenapi.diff.skip=true` (or with the `breaking-change-approved` PR label) when the PR opens.

Expected: BUILD SUCCESS.

- [ ] **Step 5: Apply Spotless and commit (closes #17)**

```bash
./mvnw -B spotless:apply
git add docs/openapi/openapi.yaml README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: Slice 6 done — flip status, multi-currency convention, regenerate OpenAPI

README Status flips Slice 6 to ✅; the Quick Start request body
example shows the new wire shape (per-posting currency +
baseMinorUnits). CLAUDE.md Key Conventions gets a bullet for the
multi-currency base-anchoring pattern + ADR-0014 link.

docs/openapi/openapi.yaml regenerated to reflect the breaking wire
format change. The Phase C PR carries the breaking-change-approved
label so openapi-diff Layer 4 doesn't block the merge.

Closes #17

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

~5 commits (Tasks 14-18). PR title: `Slice 6 Phase C: multi-currency wire format + smoke (closes #17)`. The PR MUST carry the `breaking-change-approved` label so the openapi-diff gate passes.

After merge:
- Issue #17 closes.
- `main` accepts multi-currency entries.
- The OpenAPI snapshot is the new shape.

---

## Slice 6 overall acceptance

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every Phase PR (Phase C uses `-Dopenapi.diff.skip=true` or the `breaking-change-approved` label flow).
2. CI's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /journal-entries` accepts a multi-currency entry that balances in base.
4. `POST /journal-entries` rejects a posting whose `baseAmount.currency` ≠ configured base — returns 400 + `/problems/journal/base-currency-mismatch`.
5. `POST /journal-entries` rejects entries whose base-currency debits don't equal base-currency credits — returns 400 + `/problems/journal/unbalanced`.
6. ADR-0014 + ADR README updated; `docs/openapi/openapi.yaml` regenerated.
7. Issue #17 closes when Phase C merges.

Slice 4 (#15 trial balance) plan follows once Slice 6 is on main.
