# Slice 2 — Account Aggregate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder `AccountCode` with a real `Account` aggregate (hierarchical, single-currency, leaf-only posting, soft-delete) and extend `JournalEntry.of(...)` with account-existence + active + leaf + currency-match validation, all without breaking the existing journal-entry flow.

**Architecture:** Three phases, each its own PR. Phase A is purely additive (new domain types + ADRs + ArchUnit rules; no existing code changes). Phase B wires the new types into the existing pipeline (JournalEntry signature evolution, mapper switch, service lookup, persistence + IT). Phase C adds the web layer (controller + DTOs + ResultMapper extensions + smoke).

**Tech Stack:** Same as Plan 2 — Spring Boot 4.0.3 on Java 25, JPA + Postgres + Flyway, Testcontainers, MockMvc. New domain types are pure POJOs; the `account` package has zero Spring/JPA dependencies. JPA adapter, controller, and DTOs live under `infrastructure`.

**Pre-condition:** `main` is post-spec-merge state (PR #41 merged). The branch `13-slice-2-phase-a-domain` is checked out, ready for Phase A. Plans for Slice 3 follow once this whole slice merges.

**Spec authority:** `docs/superpowers/specs/2026-05-11-slices-2-3-account-and-period-design.md`. When the spec and this plan disagree, the spec wins; flag any drift in the relevant phase PR.

**Definition of done (entire Slice):**

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every Phase PR and on `main` after each merge.
2. CI's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /accounts` with `{code:"5000", name:"Office Supplies", type:"EXPENSE", currency:"USD"}` returns 201; the new account appears in `GET /accounts`.
4. `POST /journal-entries` against the seeded `1000`/`3000` accounts continues to return 201.
5. `POST /journal-entries` against an unknown account code returns 400 + `/problems/journal/account-not-found`.
6. `POST /journal-entries` against the seeded `4000` Revenue account where the posting amount's currency is EUR returns 400 + `/problems/journal/account-currency-mismatch`.
7. ADRs 0011 and 0013 are committed; ADR README index updated.
8. Issue #13 closes when Phase C merges.

---

## File Structure

| Path | Created in | Responsibility |
|---|---|---|
| `docs/adr/0011-account-hierarchy-leaf-only-posting.md` | Phase A | ADR: Account model + leaf-only rule |
| `docs/adr/0013-journal-validation-context.md` | Phase A | ADR: validation-context pattern for I/O-free domain |
| `docs/adr/README.md` | Phase A | index updated for 0011 + 0013 |
| `src/main/java/.../domain/account/AccountType.java` | Phase A | enum with `.normalSide()` derivation |
| `src/main/java/.../domain/account/NormalSide.java` | Phase A | enum DEBIT \| CREDIT |
| `src/main/java/.../domain/account/Account.java` | Phase A | record + constructor invariants |
| `src/main/java/.../domain/account/AccountError.java` | Phase A | sealed: CodeAlreadyExists, NotFound, ParentNotFound, CycleWouldBeCreated, CodeInUseByPosting |
| `src/main/java/.../domain/account/AccountRepository.java` | Phase A | port: save, findByCode, findAll, findByCodeIn |
| `src/main/java/.../domain/journal/JournalValidationContext.java` | Phase A | record carrying `Map<AccountCode, Account>` (period field added in Slice 3) |
| `src/test/java/.../domain/account/AccountTypeTest.java` | Phase A | 5 tests: one per enum |
| `src/test/java/.../domain/account/AccountTest.java` | Phase A | record invariant tests |
| `src/test/java/.../domain/account/AccountErrorTest.java` | Phase A | sealed-interface exhaustiveness sanity |
| `src/test/java/.../domain/journal/JournalValidationContextTest.java` | Phase A | defensive copy + null checks |
| `src/test/java/.../architecture/HexagonalArchitectureTest.java` | Phase A | add `account` package whitelist + slice isolation rule |
| `src/main/java/.../domain/journal/JournalError.java` | Phase B | + four new variants |
| `src/main/java/.../domain/journal/JournalEntry.java` | Phase B | `of(...)` overload taking ctx; existing of() delegates with permissive ctx |
| `src/test/java/.../domain/journal/JournalEntryTest.java` | Phase B | new tests for the four new validation paths |
| `src/main/java/.../infrastructure/persistence/journal/JournalEntryEntityMapper.java` | Phase B | switch from `of()` to canonical constructor for reconstitute |
| `src/main/java/.../infrastructure/persistence/account/AccountEntity.java` | Phase B | JPA entity |
| `src/main/java/.../infrastructure/persistence/account/JpaAccountRepository.java` | Phase B | Spring Data interface |
| `src/main/java/.../infrastructure/persistence/account/AccountRepositoryAdapter.java` | Phase B | implements `AccountRepository` port |
| `src/main/java/.../infrastructure/persistence/account/AccountEntityMapper.java` | Phase B | entity ↔ domain mapping |
| `src/main/resources/db/migration/V2__accounts.sql` | Phase B | accounts table |
| `src/main/resources/db/migration/V4__postings_account_fk_and_seed.sql` | Phase B | FK + seed CoA (V3 is reserved for Slice 3) |
| `src/main/java/.../application/account/AccountService.java` | Phase B | use-case service |
| `src/main/java/.../application/journal/PostJournalEntryService.java` | Phase B | extended with account lookup |
| `src/test/java/.../application/account/AccountServiceTest.java` | Phase B | use-case tests with fake repo |
| `src/test/java/.../infrastructure/persistence/account/AccountRepositoryAdapterIT.java` | Phase B | Testcontainers round-trip + hierarchy |
| `src/main/java/.../infrastructure/web/account/AccountController.java` | Phase C | REST controller |
| `src/main/java/.../infrastructure/web/account/dto/*.java` | Phase C | request/response DTOs |
| `src/main/java/.../infrastructure/web/ResultMapper.java` | Phase C | extended with new variants |
| `src/test/java/.../infrastructure/web/account/AccountControllerTest.java` | Phase C | MockMvc per endpoint |
| `src/test/java/.../infrastructure/web/ResultMapperTest.java` | Phase C | extended assertions |
| `src/test/java/.../infrastructure/web/JournalEntryControllerTest.java` | Phase C | new failure-path tests |
| `src/test/java/.../smoke/ApplicationSmokeIT.java` | Phase C | extended assertions |

---

# Phase A — Domain types + ADRs (purely additive)

Each task is one commit. Phase A doesn't touch `JournalEntry.of(...)`, `PostJournalEntryService`, or any existing test — additions only. After Phase A merges, `main` compiles cleanly and all 96 existing tests still pass.

---

## Task 1: ADR-0013 — JournalValidationContext

Write the ADR first; the domain types reference it.

**Files:**
- Create: `docs/adr/0013-journal-validation-context.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0013-journal-validation-context.md`:

```markdown
# ADR-0013: Domain validation that needs external data uses a context record

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

`JournalEntry.of(...)` is the construction-time validator for journal
entries. The Plan-1 implementation checks invariants that depend only on
the entry's own data: postings non-empty, single currency, balanced,
no overflow. Slice 2 adds rules that need information *outside* the
entry: does each posting's account exist? Is it active? Is it a leaf?
Does its currency match? Slice 3 will add: is the entry's `YearMonth`
period open?

The domain layer rule (ADR-0002) forbids domain classes from depending
on Spring, JPA, or any port directly. We can't have `JournalEntry.of(...)`
call `AccountRepository.findByCode(...)` — that puts I/O in the domain.

## Decision

Domain validation that needs external data takes a **value-typed
context record** as a parameter. The application service does the I/O
(repository lookups) and packs the results into the context; the
domain consumes the context as plain values.

For `JournalEntry.of(...)`:

```java
public record JournalValidationContext(
        Map<AccountCode, Account> accounts) {

    public JournalValidationContext {
        Objects.requireNonNull(accounts, "accounts");
        accounts = Map.copyOf(accounts);
    }

    public static JournalValidationContext permissive() {
        return new JournalValidationContext(Map.of());
    }
}
```

Slice 3 will add a `PeriodStatus periodStatus` field to the same
record.

`JournalEntry.of(...)` gains a new overload:

```java
public static Result<JournalEntry, JournalError> of(
        LocalDate occurredOn, String description, List<Posting> postings,
        JournalValidationContext ctx);
```

The existing `of(occurredOn, description, postings)` overload remains;
it delegates to the new one with `JournalValidationContext.permissive()`
so historical tests that don't care about account validation keep
compiling. New callers should use the four-argument form.

## Consequences

- Domain stays I/O-free; ArchUnit's existing rules continue to pass.
- The service is the only place that does cross-aggregate lookups,
  which is the right place for it.
- New validation rules can be added by extending the context record;
  the domain method signature stays stable.
- Tests of `JournalEntry.of(...)` validation can mint contexts directly
  (no Spring needed).
- The `permissive()` factory is a small concession to backward
  compatibility. Once all callers are migrated to the four-argument
  form, we can deprecate it.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Find the row where 0013 is reserved (or insert one if not present) and flip it to:

```
| [0013](0013-journal-validation-context.md) | Domain validation that needs external data uses a context record | Accepted |
```

If 0013 isn't listed yet (recheck the index), append it in numerical order.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0013-journal-validation-context.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): 0013 domain validation needing external data uses a context record

Slice 2 adds JournalEntry.of() rules that depend on account state
(exists, active, leaf, currency-match). Domain can't reach into the
repository (ADR-0002 layering). The pattern: the service does the
lookup and packs results into a JournalValidationContext record;
JournalEntry.of() consumes plain values.

JournalValidationContext lands in this slice; Slice 3 will extend it
with a PeriodStatus field. The existing of() overload stays for
backward compatibility (delegates to permissive()).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ADR-0011 — Account hierarchy + leaf-only posting

**Files:**
- Create: `docs/adr/0011-account-hierarchy-leaf-only-posting.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0011-account-hierarchy-leaf-only-posting.md`:

```markdown
# ADR-0011: Account aggregate with hierarchy and leaf-only posting

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

Plan 1 introduced `AccountCode` as a typed-string placeholder so the
walking-skeleton journal-entry pipeline could compile. Real ledgers
need a chart of accounts with semantic structure: account *type*
(asset, liability, equity, revenue, expense), normal *side* (debit or
credit), an optional hierarchy for reporting roll-ups, and a *currency*
that constrains which postings can reference the account. Slice 2 lands
that aggregate.

## Decision

`Account` is a record:

```java
public record Account(
        AccountCode code,
        String name,
        AccountType type,
        Currency currency,
        Optional<AccountCode> parentCode,
        boolean active);
```

- **`AccountCode` is the natural primary key.** Users supply it at
  create-time; the system never mints one. Renames cascade FK updates
  via Postgres `ON UPDATE CASCADE`. No surrogate UUID.
- **Five types only:** `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`,
  `EXPENSE`. `AccountType.normalSide()` derives `DEBIT` for asset and
  expense, `CREDIT` for the rest.
- **Single currency per account.** Postings against the account must
  use the same currency. Multi-currency accounts are a Slice 6 (#17)
  concern.
- **Hierarchy via optional `parentCode`.** A tree, not a DAG.
  Re-parenting onto a descendant creates a cycle; `AccountService`
  rejects it.
- **Leaf-only posting.** `JournalEntry.of(...)` rejects postings against
  any account with children. Parents are roll-up aggregates for
  reporting, not posting targets.
- **Soft-delete via `active = false`.** Hard-delete is not exposed —
  it would orphan postings. Reactivation is allowed; rename is allowed.

## Consequences

- The chart of accounts becomes a real, queryable structure that
  reporting (Slice 4) can roll up by hierarchy.
- The `posting -> account_code` FK is added in Slice 2's V4 migration;
  postings referencing unknown codes can never be inserted again.
- Account renames preserve historical postings via FK cascade.
- We accept the constraint of single-currency accounts. Multi-currency
  postings against the same logical account (e.g., "Cash" in USD and
  EUR) require two separate accounts (`1000-USD` and `1000-EUR`).
- The leaf-only rule is enforced at `JournalEntry.of(...)` time, not at
  account-update time. This means we can re-parent a leaf to add
  children later, and re-parent the new parent's children to detach
  them — without retroactively invalidating historical postings.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Add or update the 0011 row:

```
| [0011](0011-account-hierarchy-leaf-only-posting.md) | Account aggregate with hierarchy and leaf-only posting | Accepted |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0011-account-hierarchy-leaf-only-posting.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): 0011 account aggregate with hierarchy + leaf-only posting

AccountCode stays the natural primary key (no surrogate). Five account
types with derived normal side. Single currency per account.
Hierarchy via optional parentCode (tree, not DAG); leaf-only posting
enforced at JournalEntry.of() time. Soft-delete via active flag; no
hard delete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `NormalSide` enum

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/NormalSide.java`

- [ ] **Step 1: Write the enum**

```java
package co.embracejoy.accounting.keystone.domain.account;

/** Which side of the ledger is the natural balance side for an account type. */
public enum NormalSide {
  DEBIT,
  CREDIT
}
```

No test — trivial two-value enum. Subsequent tasks exercise it via `AccountType.normalSide()`.

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/account/NormalSide.java
git commit -m "$(cat <<'EOF'
feat(domain): NormalSide enum (DEBIT | CREDIT)

The natural balance side for an account type. Derived from AccountType
via .normalSide(); not a Posting-side concept (Side stays for that).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `AccountType` enum + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountTypeTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/AccountType.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountType")
class AccountTypeTest {

  @Test
  @DisplayName("ASSET has normal side DEBIT")
  void shouldReturnDebitWhenTypeIsAsset() {
    assertEquals(NormalSide.DEBIT, AccountType.ASSET.normalSide());
  }

  @Test
  @DisplayName("EXPENSE has normal side DEBIT")
  void shouldReturnDebitWhenTypeIsExpense() {
    assertEquals(NormalSide.DEBIT, AccountType.EXPENSE.normalSide());
  }

  @Test
  @DisplayName("LIABILITY has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsLiability() {
    assertEquals(NormalSide.CREDIT, AccountType.LIABILITY.normalSide());
  }

  @Test
  @DisplayName("EQUITY has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsEquity() {
    assertEquals(NormalSide.CREDIT, AccountType.EQUITY.normalSide());
  }

  @Test
  @DisplayName("REVENUE has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsRevenue() {
    assertEquals(NormalSide.CREDIT, AccountType.REVENUE.normalSide());
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=AccountTypeTest 2>&1 | tail -10
```

Expected: `cannot find symbol class AccountType`.

- [ ] **Step 3: Implement `AccountType`**

```java
package co.embracejoy.accounting.keystone.domain.account;

/** The five standard account types. */
public enum AccountType {
  ASSET(NormalSide.DEBIT),
  LIABILITY(NormalSide.CREDIT),
  EQUITY(NormalSide.CREDIT),
  REVENUE(NormalSide.CREDIT),
  EXPENSE(NormalSide.DEBIT);

  private final NormalSide normalSide;

  AccountType(NormalSide normalSide) {
    this.normalSide = normalSide;
  }

  public NormalSide normalSide() {
    return normalSide;
  }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=AccountTypeTest 2>&1 | tail -10
```

Expected: `Tests run: 5, Failures: 0`.

- [ ] **Step 5: Spotless apply and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): AccountType enum with derived normal side

Five standard types — ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE —
each carrying its NormalSide. Assets and expenses are normal-DEBIT;
liabilities, equity, and revenue are normal-CREDIT. Five tests, one
per variant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `Account` record + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/Account.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Account")
class AccountTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode ASSETS = new AccountCode("1");

  @Test
  @DisplayName("rejects null code")
  void shouldThrowWhenCodeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(null, "Cash", AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null name")
  void shouldThrowWhenNameIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, null, AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects blank name")
  void shouldThrowWhenNameIsBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Account(CASH, "  ", AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null type")
  void shouldThrowWhenTypeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", null, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, null, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null parentCode Optional")
  void shouldThrowWhenParentOptionalIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, USD, null, true));
  }

  @Test
  @DisplayName("rejects self-parent")
  void shouldThrowWhenAccountIsItsOwnParent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.of(CASH), true));
  }

  @Test
  @DisplayName("accepts a root account (no parent)")
  void shouldConstructWhenParentAbsent() {
    Account a = new Account(ASSETS, "Assets", AccountType.ASSET, USD, Optional.empty(), true);
    assertEquals(ASSETS, a.code());
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }

  @Test
  @DisplayName("accepts a child account with a different-code parent")
  void shouldConstructWhenParentDiffersFromCode() {
    Account a = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.of(ASSETS), true);
    assertEquals(Optional.of(ASSETS), a.parentCode());
  }

  @Test
  @DisplayName("normalSide delegates to type")
  void shouldReturnTypeNormalSide() {
    Account a = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=AccountTest 2>&1 | tail -10
```

Expected: `cannot find symbol class Account`.

- [ ] **Step 3: Implement `Account`**

```java
package co.embracejoy.accounting.keystone.domain.account;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * An account in the chart of accounts.
 *
 * <p>{@link AccountCode} is the natural primary key; the user supplies it. Optional {@link
 * #parentCode()} forms a hierarchy (tree). Leaf-only posting is enforced at {@code
 * JournalEntry.of(...)} time, not here.
 */
public record Account(
    AccountCode code,
    String name,
    AccountType type,
    Currency currency,
    Optional<AccountCode> parentCode,
    boolean active) {

  public Account {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(parentCode, "parentCode");
    if (parentCode.isPresent() && parentCode.get().equals(code)) {
      throw new IllegalArgumentException("account cannot be its own parent");
    }
  }

  public NormalSide normalSide() {
    return type.normalSide();
  }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=AccountTest 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Account record (code, name, type, currency, parent, active)

Constructor enforces: non-null fields, non-blank name, account is not
its own parent. Cycle detection across multi-level hierarchies lives
in AccountService (requires repository lookups). 10 tests cover
construction invariants and the normalSide() delegation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `AccountError` sealed interface + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountErrorTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/AccountError.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountError")
class AccountErrorTest {

  private static final AccountCode CODE = new AccountCode("1000");
  private static final AccountCode PARENT = new AccountCode("1");

  @Test
  @DisplayName("CodeAlreadyExists carries the offending code")
  void codeAlreadyExistsCarriesCode() {
    AccountError e = new AccountError.CodeAlreadyExists(CODE);
    assertInstanceOf(AccountError.CodeAlreadyExists.class, e);
    assertEquals(CODE, ((AccountError.CodeAlreadyExists) e).code());
  }

  @Test
  @DisplayName("NotFound carries the queried code")
  void notFoundCarriesCode() {
    AccountError e = new AccountError.NotFound(CODE);
    assertEquals(CODE, ((AccountError.NotFound) e).code());
  }

  @Test
  @DisplayName("ParentNotFound carries the parent code")
  void parentNotFoundCarriesParent() {
    AccountError e = new AccountError.ParentNotFound(PARENT);
    assertEquals(PARENT, ((AccountError.ParentNotFound) e).parentCode());
  }

  @Test
  @DisplayName("CycleWouldBeCreated carries the offending child and target parent")
  void cycleCarriesBothCodes() {
    AccountError e = new AccountError.CycleWouldBeCreated(CODE, PARENT);
    AccountError.CycleWouldBeCreated c = (AccountError.CycleWouldBeCreated) e;
    assertEquals(CODE, c.child());
    assertEquals(PARENT, c.proposedParent());
  }

  @Test
  @DisplayName("CodeInUseByPosting carries the rename-target code")
  void codeInUseCarriesCode() {
    AccountError e = new AccountError.CodeInUseByPosting(CODE);
    assertEquals(CODE, ((AccountError.CodeInUseByPosting) e).code());
  }

  @Test
  @DisplayName("AccountError is sealed and lists every variant")
  void sealedListIsComplete() {
    // Five permitted subtypes — pin the count so any future variant trips a sealed-switch
    // somewhere or this test.
    assertEquals(5, AccountError.class.getPermittedSubclasses().length);
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=AccountErrorTest 2>&1 | tail -10
```

Expected: `cannot find symbol class AccountError`.

- [ ] **Step 3: Implement `AccountError`**

```java
package co.embracejoy.accounting.keystone.domain.account;

/** Errors raised by {@code AccountService} operations. */
public sealed interface AccountError {

  /** Trying to create an account whose code is already in use. */
  record CodeAlreadyExists(AccountCode code) implements AccountError {}

  /** The referenced account does not exist. */
  record NotFound(AccountCode code) implements AccountError {}

  /** The supplied parent code does not exist. */
  record ParentNotFound(AccountCode parentCode) implements AccountError {}

  /** Setting {@code child}'s parent to {@code proposedParent} would create a cycle. */
  record CycleWouldBeCreated(AccountCode child, AccountCode proposedParent)
      implements AccountError {}

  /** Renaming to {@code code} would clash with a code already in use by an existing account. */
  record CodeInUseByPosting(AccountCode code) implements AccountError {}
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=AccountErrorTest 2>&1 | tail -10
```

Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): AccountError sealed type with 5 variants

CodeAlreadyExists, NotFound, ParentNotFound, CycleWouldBeCreated,
CodeInUseByPosting. Exhaustive switch on this sealed interface will
catch new variants at compile time (per ADR-0004 pattern).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `AccountRepository` port

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/AccountRepository.java`

No test for the interface itself; Phase B's `AccountServiceTest` exercises it via a fake.

- [ ] **Step 1: Write the port**

```java
package co.embracejoy.accounting.keystone.domain.account;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistence port for {@link Account} aggregates. */
public interface AccountRepository {

  /**
   * Persist a new account.
   *
   * @return {@code Success(saved)} or {@code Failure(CodeAlreadyExists)} when the natural key
   *     clashes
   */
  Result<Account, AccountError> save(Account account);

  /**
   * Persist an update to an existing account. Distinct from {@code save(...)} so the adapter can
   * detect "does not exist" vs "duplicate" — different SQL paths, different error returns.
   *
   * @return {@code Success(updated)} or {@code Failure(NotFound)} when the account doesn't exist
   */
  Result<Account, AccountError> update(Account account);

  /** Find an account by its primary key. */
  Optional<Account> findByCode(AccountCode code);

  /** All accounts in code order. */
  List<Account> findAll();

  /**
   * Batch lookup for {@code JournalEntry.of(...)} validation. Returns only matched codes — missing
   * codes simply don't appear in the map.
   */
  Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes);

  /** True if the account has at least one child (used by the leaf-only-posting rule). */
  boolean hasChildren(AccountCode code);
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): AccountRepository port

save/update split lets the adapter distinguish "code clash" from
"missing target" without an existence pre-check. findByCodeIn is the
batch lookup used by JournalEntry validation. hasChildren is used by
the leaf-only-posting rule. JPA adapter lands in Phase B.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `JournalValidationContext` + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContextTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContext.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalValidationContext")
class JournalValidationContextTest {

  private static final Currency USD = Currency.getInstance("USD");

  private static Account cash() {
    return new Account(
        new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty(), true);
  }

  @Test
  @DisplayName("rejects null accounts map")
  void shouldThrowWhenAccountsNull() {
    assertThrows(NullPointerException.class, () -> new JournalValidationContext(null));
  }

  @Test
  @DisplayName("permissive() returns an empty-accounts context")
  void shouldReturnEmptyAccountsWhenPermissive() {
    JournalValidationContext ctx = JournalValidationContext.permissive();
    assertEquals(Map.of(), ctx.accounts());
  }

  @Test
  @DisplayName("accounts map is defensively copied")
  void shouldDefensivelyCopyAccounts() {
    Map<AccountCode, Account> mutable = new HashMap<>();
    Account a = cash();
    mutable.put(a.code(), a);
    JournalValidationContext ctx = new JournalValidationContext(mutable);
    mutable.clear();
    assertEquals(1, ctx.accounts().size());
    assertSame(a, ctx.accounts().get(a.code()));
  }

  @Test
  @DisplayName("accounts map is unmodifiable")
  void shouldRejectModificationOfAccounts() {
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(cash().code(), cash()));
    assertThrows(UnsupportedOperationException.class, () -> ctx.accounts().clear());
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=JournalValidationContextTest 2>&1 | tail -10
```

Expected: `cannot find symbol class JournalValidationContext`.

- [ ] **Step 3: Implement**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Map;
import java.util.Objects;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry. The application
 * service does the I/O (account lookups, future period lookup) and packs results in here.
 *
 * <p>Slice 3 will add a {@code PeriodStatus periodStatus} field.
 */
public record JournalValidationContext(Map<AccountCode, Account> accounts) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    accounts = Map.copyOf(accounts);
  }

  /** Empty-accounts context for tests and callers that don't need account validation yet. */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of());
  }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=JournalValidationContextTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalValidationContext record

Holds the looked-up accounts that JournalEntry.of() needs to validate
new entries. Defensive Map.copyOf in the canonical constructor; the
public accessor is unmodifiable. permissive() factory returns an
empty-accounts context for backward compatibility with existing of()
callers. Slice 3 adds a PeriodStatus field.

Captured in ADR-0013.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: ArchUnit additions for the `account` package

The two new ArchUnit rules from the spec §8.7. The first one is a no-op until Slice 3 adds `period`; both go in now so Slice 3's PR doesn't need to touch this file.

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java`

- [ ] **Step 1: Append the two slice-isolation rules**

In `HexagonalArchitectureTest`, add (alongside the existing rules):

```java
  @ArchTest
  static final ArchRule ACCOUNT_DOES_NOT_DEPEND_ON_PERIOD =
      noClasses()
          .that()
          .resideInAPackage("..domain.account..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain.period..");

  @ArchTest
  static final ArchRule PERIOD_DOES_NOT_DEPEND_ON_ACCOUNT =
      noClasses()
          .that()
          .resideInAPackage("..domain.period..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain.account..");
```

The second rule trivially passes in Slice 2 (the `period` package doesn't exist yet); both rules apply once Slice 3 adds `period`.

- [ ] **Step 2: Verify**

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -10
```

Expected: `Tests run: 14, Failures: 0` (was 12; added 2).

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(architecture): account/period package isolation rules

Add two ArchUnit rules forbidding cross-dependency between the
account and period packages. The period rule passes trivially in
Slice 2 (period package doesn't exist yet) and becomes meaningful
once Slice 3 lands.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Phase A acceptance — final verify

**Files:** none

- [ ] **Step 1: Cold-cache verify**

```bash
./mvnw -B clean verify 2>&1 | tail -20
```

Expected: BUILD SUCCESS. Total tests: 96 (Plan 3 end) + 5 (AccountType) + 10 (Account) + 6 (AccountError) + 4 (JournalValidationContext) + 2 (ArchUnit) = **123**.

- [ ] **Step 2: Confirm Phase A is additive (no existing code touched)**

```bash
git diff main..HEAD --stat | head -20
```

Every line should be additions to new files or to the two existing docs (ADR README, ArchUnit test). No changes to `JournalEntry.java`, `PostJournalEntryService.java`, or any other Plan-1/2/3 code.

- [ ] **Step 3: No commit; Phase A is done**

Push the branch and open the PR. PR title: `Slice 2 Phase A: account aggregate domain types + ADRs 0011, 0013`.

---

## Phase A acceptance

10 commits (plan doc isn't on this branch yet — see the bootstrap note below). All tests pass. No existing code changed. ADRs 0011 + 0013 committed; ADR README updated. Two new ArchUnit rules.

**Bootstrap note:** the plan document `2026-05-11-slice-2-account-aggregate.md` lands as part of this branch's first commit (before Task 1). Add it with:

```bash
git add docs/superpowers/plans/2026-05-11-slice-2-account-aggregate.md
git commit -m "docs(plan): Slice 2 — Account aggregate (Phases A-C)

Three phases, three PRs: A is purely additive (domain types +
ADRs 0011, 0013), B wires validation into JournalEntry.of() and
adds persistence + service, C lands web layer + smoke. Spec
authority: docs/superpowers/specs/2026-05-11-slices-2-3-account-and-period-design.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Phase B — Wiring (validation + persistence + application)

Phase B updates the existing JournalEntry pipeline to use the new types, adds the JPA adapter for `AccountRepository`, lands the Flyway V2 + V4 migrations, and adds the `AccountService` use-case layer. Each task is one commit; full TDD where it adds value.

---

## Task 11: Extend `JournalError` with the four account-related variants

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`

- [ ] **Step 1: Add the four variants**

In `JournalError.java`, after the existing variants, add:

```java
  /** A posting references an unknown account code. */
  record AccountNotFound(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** A posting references a deactivated account. */
  record AccountInactive(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** A posting targets an account that has child accounts (not a leaf). */
  record AccountNotALeaf(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** Posting amount's currency differs from the account's currency. */
  record AccountCurrencyMismatch(
      co.embracejoy.accounting.keystone.domain.account.AccountCode code,
      java.util.Currency expectedByAccount,
      java.util.Currency actualOnPosting)
      implements JournalError {}
```

(Use the fully-qualified `AccountCode` to avoid changing the import block at the top — keeps the diff small. If your formatter pulls them up to imports, that's fine too.)

- [ ] **Step 2: Verify compile + existing tests still pass**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all existing tests pass; no new tests yet (Task 12 adds them).

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalError gains 4 account-related variants

AccountNotFound, AccountInactive, AccountNotALeaf,
AccountCurrencyMismatch. Used by the new JournalEntry.of() overload
in the next commit. Existing of() callers don't trigger these.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Extend `JournalEntry.of(...)` with the validation-context overload

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`

The validation order from spec §4.5 (Slice 2 portion):

1. Postings non-empty
2. Single currency on postings
3. **Per-posting account validation (NEW):** exists → active → leaf → currency match
4. Posting sums don't overflow
5. Balanced (debits == credits)

The "leaf" check requires `AccountRepository.hasChildren(code)` — but the context doesn't carry that. Two options:
- (a) Pack the leaf-ness into a separate map `Map<AccountCode, Boolean> hasChildren` in the context.
- (b) Look it up via a `Predicate<AccountCode>` in the context.

Per spec §4.5, option (a) is the simpler pick — the service computes it once during the lookup. The context grows by one field for now; Slice 3 also adds a field.

**Update `JournalValidationContext` to carry the leaf map.** This is technically a Task 8 amendment, so do this small refactor first:

- [ ] **Step 1: Extend `JournalValidationContext`**

Update `JournalValidationContext.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record JournalValidationContext(
    Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of(), Set.of());
  }
}
```

Update `JournalValidationContextTest` for the new field (one new test for null-rejection of `nonLeafCodes`; update construction sites). The existing `accounts`-only tests stay valid — they just construct with `Set.of()` for the leaf set.

Run `./mvnw -B test -Dtest=JournalValidationContextTest` — all tests still pass after updates.

- [ ] **Step 2: Add tests for the four new validation paths in `JournalEntryTest`**

Append to `JournalEntryTest.java` (existing test file; uses helpers `debit(...)`, `credit(...)`, `validEntry()` from Plan 2's tests — those keep working with the old 3-arg `of(...)` overload which delegates to permissive context):

```java
  @Test
  @DisplayName("of(ctx) returns Failure(AccountNotFound) when a posting account is missing")
  void shouldReturnAccountNotFoundWhenAccountAbsent() {
    JournalValidationContext ctx = new JournalValidationContext(Map.of(), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "x",
            List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)),
            ctx);
    JournalError.AccountNotFound e =
        (JournalError.AccountNotFound) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountInactive) when account is deactivated")
  void shouldReturnAccountInactiveWhenAccountInactive() {
    Account cashInactive =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), false);
    Account equity =
        new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cashInactive, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "x",
            List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)),
            ctx);
    assertInstanceOf(JournalError.AccountInactive.class,
        ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountNotALeaf) when account has children")
  void shouldReturnAccountNotALeafWhenAccountHasChildren() {
    Account cash =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity =
        new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of(CASH));
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "x",
            List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)),
            ctx);
    JournalError.AccountNotALeaf e =
        (JournalError.AccountNotALeaf) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountCurrencyMismatch) when currency differs")
  void shouldReturnAccountCurrencyMismatchWhenCurrencyDiffers() {
    Currency eur = Currency.getInstance("EUR");
    Account cashEur =
        new Account(CASH, "Cash EUR", AccountType.ASSET, eur, Optional.empty(), true);
    Account equityEur =
        new Account(EQUITY, "Equity EUR", AccountType.EQUITY, eur, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cashEur, EQUITY, equityEur), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "x",
            List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)),
            ctx);
    JournalError.AccountCurrencyMismatch e =
        (JournalError.AccountCurrencyMismatch)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(eur, e.expectedByAccount());
    assertEquals(USD, e.actualOnPosting());
  }

  @Test
  @DisplayName("of(ctx) returns Success when all account checks pass")
  void shouldReturnSuccessWhenAllAccountChecksPass() {
    Account cash =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity =
        new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "opening",
            List.of(debit(CASH, 100L, USD), credit(EQUITY, 100L, USD)),
            ctx);
    assertInstanceOf(Result.Success.class, r);
  }
```

You'll need new imports:

```java
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.journal.JournalValidationContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
```

- [ ] **Step 3: Verify the new tests fail to compile**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -15
```

Expected: compile error — `JournalEntry.of` doesn't have a 4-arg overload yet.

- [ ] **Step 4: Update `JournalEntry.java` to add the four-arg overload**

Update `JournalEntry.java`:

```java
  /**
   * Build a journal entry with account validation. The {@link JournalValidationContext} carries
   * looked-up account state needed for the new validation rules.
   */
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

    Set<Currency> currencies =
        postings.stream().map(p -> p.amount().currency()).collect(Collectors.toUnmodifiableSet());
    if (currencies.size() > 1) {
      return Result.failure(new JournalError.MixedCurrencies(currencies));
    }
    Currency currency = currencies.iterator().next();

    for (Posting p : postings) {
      AccountCode code = p.account();
      Account account = ctx.accounts().get(code);
      if (account == null) {
        if (ctx.accounts().isEmpty() && ctx.nonLeafCodes().isEmpty()) {
          // permissive context: skip account-existence check
          continue;
        }
        return Result.failure(new JournalError.AccountNotFound(code));
      }
      if (!account.active()) {
        return Result.failure(new JournalError.AccountInactive(code));
      }
      if (ctx.nonLeafCodes().contains(code)) {
        return Result.failure(new JournalError.AccountNotALeaf(code));
      }
      if (!account.currency().equals(p.amount().currency())) {
        return Result.failure(
            new JournalError.AccountCurrencyMismatch(
                code, account.currency(), p.amount().currency()));
      }
    }

    // Existing balance check
    Money zero = new Money(0L, currency);
    return sum(postings, Side.DEBIT, zero)
        .flatMap(
            debits ->
                sum(postings, Side.CREDIT, zero)
                    .flatMap(
                        credits -> {
                          if (debits.minorUnits() != credits.minorUnits()) {
                            return Result.failure(
                                new JournalError.Unbalanced(debits, credits));
                          }
                          return Result.success(
                              new JournalEntry(occurredOn, description, currency, postings));
                        }));
  }

  /**
   * Backward-compatible overload. Equivalent to {@link
   * #of(LocalDate, String, List, JournalValidationContext)} with a permissive context (no account
   * checks). Existing callers and historical tests use this; new callers should use the four-arg
   * form with a real context.
   */
  public static Result<JournalEntry, JournalError> of(
      LocalDate occurredOn, String description, List<Posting> postings) {
    return of(occurredOn, description, postings, JournalValidationContext.permissive());
  }
```

Imports to add:

```java
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
```

The detection of "permissive context" via `ctx.accounts().isEmpty() && ctx.nonLeafCodes().isEmpty()` is a deliberate shortcut: when both are empty, skip account-existence checks. Real production calls always pass non-empty accounts (because the service did the lookup); empty context is only used by historical tests that don't care.

- [ ] **Step 5: Verify all tests pass**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all green; new tests count = 5; existing count unchanged.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntry.of(ctx) overload with account validation

New four-arg form validates each posting against the context's
accounts map and non-leaf set in order: exists → active → leaf →
currency-match. Five new tests cover all four new variants plus the
all-checks-pass success path.

Existing three-arg of(...) overload delegates to permissive context,
keeping every historical test compiling unchanged. ADR-0013 captures
the pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Switch the journal-entry mapper to the canonical constructor

The JPA mapper currently calls `JournalEntry.of(...)` to reconstitute a persisted entry. After Task 12, that would either accidentally trigger account-existence checks (if we pass a context with the right shape) or use the permissive overload (which still runs every other validation needlessly). Cleaner: bypass validation entirely on read by using the record's canonical constructor.

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java`

- [ ] **Step 1: Switch reconstitution from `of` to `new`**

In `JournalEntryEntityMapper.java`, find the `toDomain(JournalEntryEntity)` method and replace the `JournalEntry.of(...)` call with a direct construction:

```java
  static PersistedJournalEntry toDomain(JournalEntryEntity entity) {
    Currency currency = Currency.getInstance(entity.getCurrency());
    java.util.List<Posting> postings =
        entity.getPostings().stream()
            .map(
                pe ->
                    new Posting(
                        new AccountCode(pe.getAccountCode()),
                        Side.valueOf(pe.getSide()),
                        new Money(pe.getAmountMinorUnits(), currency)))
            .toList();
    JournalEntry entry =
        new JournalEntry(
            entity.getOccurredOn(), entity.getDescription(), currency, postings);
    return new PersistedJournalEntry(new JournalEntryId(entity.getId()), entry);
  }
```

The canonical record constructor runs null checks and defensive-copies postings (per Plan 1's implementation); it does NOT run the value-level invariants like balanced/single-currency. That's correct for reconstitute — the data on disk is already valid.

- [ ] **Step 2: Verify**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. The `JpaJournalEntryRepositoryIT` round-trip still passes (it persists then reads back).

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
refactor(persistence): mapper reconstitutes via canonical constructor

JournalEntryEntityMapper.toDomain was calling JournalEntry.of() to
rebuild persisted entries; that re-ran the balanced/single-currency
invariants on every read. With Slice 2's account validation arriving,
re-running of() would either need an account context (wrong — old
postings reference current account state, which may differ) or use
the permissive overload (wasteful).

Switch to the record's canonical constructor: null checks +
defensive-copy of postings, no value-level invariants. Plan-2's
round-trip IT keeps passing.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Flyway V2 migration — accounts table

**Files:**
- Create: `src/main/resources/db/migration/V2__accounts.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE accounts (
    code          VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    type          VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    currency      CHAR(3)      NOT NULL,
    parent_code   VARCHAR(64)  REFERENCES accounts(code) ON UPDATE CASCADE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT account_not_self_parent CHECK (parent_code IS NULL OR parent_code <> code)
);

CREATE INDEX idx_accounts_parent_code ON accounts(parent_code);
CREATE INDEX idx_accounts_active_true ON accounts(active) WHERE active = TRUE;
```

- [ ] **Step 2: Verify Flyway runs cleanly**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. The Testcontainers Postgres in `JpaJournalEntryRepositoryIT` runs Flyway and creates the new table.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V2__accounts.sql
git commit -m "$(cat <<'EOF'
feat(persistence): Flyway V2 — accounts table

Natural primary key on code. Self-referential FK on parent_code with
ON UPDATE CASCADE so renames flow. CHECK constraint forbids
self-parent. Two indexes: parent_code (for tree queries) and a
partial index on active=TRUE (since most reads filter active accounts).

V4 (in a later task) adds the FK from postings.account_code to
accounts.code and seeds a minimal chart of accounts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: JPA entity, Spring Data repo, adapter, and entity mapper

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/AccountEntity.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/JpaAccountRepository.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/AccountEntityMapper.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/AccountRepositoryAdapter.java`

- [ ] **Step 1: Write `AccountEntity`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "accounts")
class AccountEntity {

  @Id
  @Column(name = "code", nullable = false, length = 64, updatable = false)
  private String code;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "type", nullable = false, length = 16)
  private String type;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "parent_code", length = 64)
  private String parentCode;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false)
  private Instant updatedAt;

  protected AccountEntity() {
    // JPA required no-arg constructor
  }

  AccountEntity(
      String code, String name, String type, String currency, String parentCode, boolean active) {
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
    this.active = active;
  }

  String getCode() {
    return code;
  }

  String getName() {
    return name;
  }

  String getType() {
    return type;
  }

  String getCurrency() {
    return currency;
  }

  String getParentCode() {
    return parentCode;
  }

  boolean isActive() {
    return active;
  }

  void setName(String name) {
    this.name = name;
  }

  void setParentCode(String parentCode) {
    this.parentCode = parentCode;
  }

  void setActive(boolean active) {
    this.active = active;
  }
}
```

- [ ] **Step 2: Write `JpaAccountRepository`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaAccountRepository extends JpaRepository<AccountEntity, String> {

  List<AccountEntity> findAllByCodeIn(Collection<String> codes);

  boolean existsByParentCode(String parentCode);
}
```

- [ ] **Step 3: Write `AccountEntityMapper`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import java.util.Currency;
import java.util.Optional;

final class AccountEntityMapper {

  private AccountEntityMapper() {}

  static AccountEntity toEntity(Account a) {
    return new AccountEntity(
        a.code().value(),
        a.name(),
        a.type().name(),
        a.currency().getCurrencyCode(),
        a.parentCode().map(AccountCode::value).orElse(null),
        a.active());
  }

  static Account toDomain(AccountEntity e) {
    return new Account(
        new AccountCode(e.getCode()),
        e.getName(),
        AccountType.valueOf(e.getType()),
        Currency.getInstance(e.getCurrency()),
        Optional.ofNullable(e.getParentCode()).map(AccountCode::new),
        e.isActive());
  }
}
```

- [ ] **Step 4: Write `AccountRepositoryAdapter`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccountRepositoryAdapter implements AccountRepository {

  private final JpaAccountRepository jpa;

  public AccountRepositoryAdapter(JpaAccountRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Result<Account, AccountError> save(Account account) {
    if (jpa.existsById(account.code().value())) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
    try {
      AccountEntity saved = jpa.save(AccountEntityMapper.toEntity(account));
      return Result.success(AccountEntityMapper.toDomain(saved));
    } catch (DataIntegrityViolationException ex) {
      // Race: a parallel insert created the same code between the existsById check and save.
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
  }

  @Override
  public Result<Account, AccountError> update(Account account) {
    if (!jpa.existsById(account.code().value())) {
      return Result.failure(new AccountError.NotFound(account.code()));
    }
    AccountEntity entity =
        jpa.findById(account.code().value()).orElseThrow();
    entity.setName(account.name());
    entity.setParentCode(account.parentCode().map(AccountCode::value).orElse(null));
    entity.setActive(account.active());
    return Result.success(AccountEntityMapper.toDomain(jpa.save(entity)));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> findByCode(AccountCode code) {
    return jpa.findById(code.value()).map(AccountEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Account> findAll() {
    return jpa.findAll().stream().map(AccountEntityMapper::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
    List<String> ids = codes.stream().map(AccountCode::value).toList();
    Map<AccountCode, Account> out = new LinkedHashMap<>();
    for (AccountEntity e : jpa.findAllByCodeIn(ids)) {
      Account a = AccountEntityMapper.toDomain(e);
      out.put(a.code(), a);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasChildren(AccountCode code) {
    return jpa.existsByParentCode(code.value());
  }
}
```

- [ ] **Step 5: Verify compile + verify**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(persistence): JpaAccountRepository adapter wired through Spring Data

Four files:
- AccountEntity: JPA entity matching V2 schema.
- JpaAccountRepository: Spring Data interface with findAllByCodeIn
  (batch) and existsByParentCode (hasChildren leaf check).
- AccountEntityMapper: domain ↔ entity.
- AccountRepositoryAdapter: @Repository @Transactional, implements
  the domain port. save() returns CodeAlreadyExists on key clash
  (Postgres unique violation is also caught defensively). update()
  returns NotFound when target doesn't exist.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: `AccountRepositoryAdapterIT` — Testcontainers integration

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/AccountRepositoryAdapterIT.java`

- [ ] **Step 1: Write the IT**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.Map;
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

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@DisplayName("AccountRepositoryAdapter (integration)")
class AccountRepositoryAdapterIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired AccountRepositoryAdapter repository;

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode ASSETS = new AccountCode("1");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode RECEIVABLES = new AccountCode("1100");

  private static Account asset(AccountCode code, String name, Optional<AccountCode> parent) {
    return new Account(code, name, AccountType.ASSET, USD, parent, true);
  }

  @Test
  @DisplayName("save persists a new account and findByCode reads it back")
  void shouldRoundTripWhenSavingThenReading() {
    Account a = asset(CASH, "Cash", Optional.empty());
    Result<Account, AccountError> r = repository.save(a);

    assertThat(r).isInstanceOf(Result.Success.class);
    Optional<Account> found = repository.findByCode(CASH);
    assertThat(found).contains(a);
  }

  @Test
  @DisplayName("save returns CodeAlreadyExists on duplicate key")
  void shouldReturnCodeAlreadyExistsWhenDuplicate() {
    repository.save(asset(CASH, "Cash", Optional.empty()));

    Result<Account, AccountError> r = repository.save(asset(CASH, "Cash Again", Optional.empty()));

    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeAlreadyExists.class);
  }

  @Test
  @DisplayName("update returns NotFound when account doesn't exist")
  void shouldReturnNotFoundWhenUpdatingMissing() {
    Result<Account, AccountError> r =
        repository.update(asset(new AccountCode("9999"), "Ghost", Optional.empty()));
    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.NotFound.class);
  }

  @Test
  @DisplayName("findByCodeIn returns only matched codes")
  void shouldReturnOnlyMatchedCodesWhenBatchLooking() {
    repository.save(asset(CASH, "Cash", Optional.empty()));
    repository.save(asset(RECEIVABLES, "Receivables", Optional.empty()));

    Map<AccountCode, Account> found =
        repository.findByCodeIn(Set.of(CASH, RECEIVABLES, new AccountCode("missing")));

    assertThat(found).hasSize(2).containsKeys(CASH, RECEIVABLES);
  }

  @Test
  @DisplayName("hasChildren is true when at least one child exists")
  void shouldReturnTrueWhenAccountHasChildren() {
    repository.save(asset(ASSETS, "Assets", Optional.empty()));
    repository.save(asset(CASH, "Cash", Optional.of(ASSETS)));

    assertThat(repository.hasChildren(ASSETS)).isTrue();
    assertThat(repository.hasChildren(CASH)).isFalse();
  }
}
```

- [ ] **Step 2: Run**

```bash
./mvnw -B verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS; total tests increases by 5 IT tests.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
test(persistence): Testcontainers integration test for AccountRepositoryAdapter

Five tests: round-trip save+findByCode, duplicate-key CodeAlreadyExists,
update of missing returns NotFound, batch findByCodeIn returns only
matched, hasChildren reflects FK presence.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: `AccountService` + TDD with fake repo

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/account/AccountServiceTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/account/AccountService.java`

- [ ] **Step 1: Write `AccountServiceTest` with a fake `AccountRepository`**

```java
package co.embracejoy.accounting.keystone.application.account;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountService")
class AccountServiceTest {

  private FakeAccountRepository repo;
  private AccountService service;
  private static final Currency USD = Currency.getInstance("USD");

  @BeforeEach
  void setup() {
    repo = new FakeAccountRepository();
    service = new AccountService(repo);
  }

  @Test
  @DisplayName("create persists when code is fresh")
  void shouldCreateWhenCodeFresh() {
    Result<Account, AccountError> r =
        service.create(
            new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode).containsKey(new AccountCode("1000"));
  }

  @Test
  @DisplayName("create returns CodeAlreadyExists on duplicate")
  void shouldReturnDuplicateWhenCodeExists() {
    service.create(new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    Result<Account, AccountError> r =
        service.create(
            new AccountCode("1000"), "Cash 2", AccountType.ASSET, USD, Optional.empty());
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeAlreadyExists.class);
  }

  @Test
  @DisplayName("create returns ParentNotFound when parent absent")
  void shouldReturnParentNotFoundWhenParentMissing() {
    Result<Account, AccountError> r =
        service.create(
            new AccountCode("1000"),
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.of(new AccountCode("ghost")));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.ParentNotFound.class);
  }

  @Test
  @DisplayName("setParent returns CycleWouldBeCreated when target is a descendant")
  void shouldDetectCycleWhenReparenting() {
    service.create(new AccountCode("1"), "Assets", AccountType.ASSET, USD, Optional.empty());
    service.create(
        new AccountCode("10"), "Current", AccountType.ASSET, USD, Optional.of(new AccountCode("1")));
    service.create(
        new AccountCode("100"),
        "Cash",
        AccountType.ASSET,
        USD,
        Optional.of(new AccountCode("10")));

    // Re-parent "1" under "100" → cycle (1 → 10 → 100 → 1).
    Result<Account, AccountError> r =
        service.setParent(new AccountCode("1"), Optional.of(new AccountCode("100")));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CycleWouldBeCreated.class);
  }

  @Test
  @DisplayName("rename to an unused code succeeds")
  void shouldRenameWhenNewCodeFree() {
    service.create(new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    Result<Account, AccountError> r =
        service.rename(new AccountCode("1000"), new AccountCode("1001"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode).doesNotContainKey(new AccountCode("1000"));
    assertThat(repo.byCode).containsKey(new AccountCode("1001"));
  }

  @Test
  @DisplayName("rename to an in-use code returns CodeInUseByPosting")
  void shouldReturnCodeInUseWhenRenameClashes() {
    service.create(new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.create(
        new AccountCode("1001"), "Petty Cash", AccountType.ASSET, USD, Optional.empty());

    Result<Account, AccountError> r =
        service.rename(new AccountCode("1000"), new AccountCode("1001"));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeInUseByPosting.class);
  }

  @Test
  @DisplayName("deactivate marks active=false and is idempotent")
  void shouldDeactivateIdempotently() {
    service.create(new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.deactivate(new AccountCode("1000"));
    Result<Account, AccountError> r = service.deactivate(new AccountCode("1000"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode.get(new AccountCode("1000")).active()).isFalse();
  }

  @Test
  @DisplayName("reactivate flips active back to true")
  void shouldReactivate() {
    service.create(new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.deactivate(new AccountCode("1000"));
    Result<Account, AccountError> r = service.reactivate(new AccountCode("1000"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode.get(new AccountCode("1000")).active()).isTrue();
  }

  // ---- fake ----

  private static final class FakeAccountRepository implements AccountRepository {
    final Map<AccountCode, Account> byCode = new HashMap<>();
    final Set<AccountCode> parents = new HashSet<>();

    @Override
    public Result<Account, AccountError> save(Account account) {
      if (byCode.containsKey(account.code())) {
        return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
      }
      byCode.put(account.code(), account);
      account.parentCode().ifPresent(parents::add);
      return Result.success(account);
    }

    @Override
    public Result<Account, AccountError> update(Account account) {
      if (!byCode.containsKey(account.code())) {
        return Result.failure(new AccountError.NotFound(account.code()));
      }
      byCode.put(account.code(), account);
      // rebuild parents set
      parents.clear();
      for (Account a : byCode.values()) {
        a.parentCode().ifPresent(parents::add);
      }
      return Result.success(account);
    }

    @Override
    public Optional<Account> findByCode(AccountCode code) {
      return Optional.ofNullable(byCode.get(code));
    }

    @Override
    public List<Account> findAll() {
      return new ArrayList<>(byCode.values());
    }

    @Override
    public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
      Map<AccountCode, Account> out = new HashMap<>();
      for (AccountCode c : codes) {
        Account a = byCode.get(c);
        if (a != null) out.put(c, a);
      }
      return out;
    }

    @Override
    public boolean hasChildren(AccountCode code) {
      return parents.contains(code);
    }
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=AccountServiceTest 2>&1 | tail -10
```

Expected: `cannot find symbol class AccountService`.

- [ ] **Step 3: Implement `AccountService`**

```java
package co.embracejoy.accounting.keystone.application.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class AccountService {

  private final AccountRepository repository;

  public AccountService(AccountRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public Result<Account, AccountError> create(
      AccountCode code,
      String name,
      AccountType type,
      Currency currency,
      Optional<AccountCode> parentCode) {
    if (parentCode.isPresent() && repository.findByCode(parentCode.get()).isEmpty()) {
      return Result.failure(new AccountError.ParentNotFound(parentCode.get()));
    }
    return repository.save(new Account(code, name, type, currency, parentCode, true));
  }

  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    Optional<Account> opt = repository.findByCode(existing);
    if (opt.isEmpty()) {
      return Result.failure(new AccountError.NotFound(existing));
    }
    if (repository.findByCode(newCode).isPresent()) {
      return Result.failure(new AccountError.CodeInUseByPosting(newCode));
    }
    Account a = opt.get();
    // Delete-then-recreate is the simplest path for a natural-key rename; for now, use update
    // with a re-keyed entity. The adapter handles ON UPDATE CASCADE on the FK.
    // For the in-test fake we emulate by removing the old, adding the new.
    Account renamed =
        new Account(newCode, a.name(), a.type(), a.currency(), a.parentCode(), a.active());
    // Adapter implementation note: real JPA rename happens via native SQL UPDATE accounts SET
    // code = :newCode WHERE code = :existing. The fake achieves it via update+remove.
    repository.update(a);  // ensure exists check
    // Re-key in the repo: remove the old, save as new.
    deleteFromRepo(existing);
    return repository.save(renamed);
  }

  // Helper to support rename — real adapter overrides this via a custom SQL path;
  // see AccountRepositoryAdapter for production behavior.
  private void deleteFromRepo(AccountCode code) {
    // Default no-op: production adapter performs an UPDATE via SQL; fake repo handles removal
    // through a separate mechanism. (This path is only exercised in tests with the fake.)
    repository
        .findByCode(code)
        .ifPresent(
            a -> {
              // No-op in production; the fake repo overrides via its own internal removal.
            });
  }

  public Result<Account, AccountError> setParent(
      AccountCode code, Optional<AccountCode> newParentCode) {
    Optional<Account> opt = repository.findByCode(code);
    if (opt.isEmpty()) {
      return Result.failure(new AccountError.NotFound(code));
    }
    if (newParentCode.isPresent()) {
      if (repository.findByCode(newParentCode.get()).isEmpty()) {
        return Result.failure(new AccountError.ParentNotFound(newParentCode.get()));
      }
      if (wouldCreateCycle(code, newParentCode.get())) {
        return Result.failure(new AccountError.CycleWouldBeCreated(code, newParentCode.get()));
      }
    }
    Account a = opt.get();
    return repository.update(
        new Account(a.code(), a.name(), a.type(), a.currency(), newParentCode, a.active()));
  }

  public Result<Account, AccountError> deactivate(AccountCode code) {
    return setActive(code, false);
  }

  public Result<Account, AccountError> reactivate(AccountCode code) {
    return setActive(code, true);
  }

  public Optional<Account> findByCode(AccountCode code) {
    return repository.findByCode(code);
  }

  public List<Account> findAll() {
    return repository.findAll();
  }

  private Result<Account, AccountError> setActive(AccountCode code, boolean active) {
    Optional<Account> opt = repository.findByCode(code);
    if (opt.isEmpty()) {
      return Result.failure(new AccountError.NotFound(code));
    }
    Account a = opt.get();
    if (a.active() == active) {
      return Result.success(a); // idempotent
    }
    return repository.update(
        new Account(a.code(), a.name(), a.type(), a.currency(), a.parentCode(), active));
  }

  private boolean wouldCreateCycle(AccountCode child, AccountCode proposedParent) {
    Set<AccountCode> visited = new HashSet<>();
    AccountCode current = proposedParent;
    while (current != null) {
      if (current.equals(child)) {
        return true;
      }
      if (!visited.add(current)) {
        return false; // shouldn't happen with a valid tree, but guard against
      }
      Optional<Account> next = repository.findByCode(current);
      current = next.flatMap(Account::parentCode).orElse(null);
    }
    return false;
  }
}
```

**Caveat on rename:** the in-memory fake repo and the JPA adapter take different paths for renaming a natural-key entity. The fake's `update` swaps the key (because the adapter would do an SQL `UPDATE ... SET code = ...` that cascades via FK). For Slice 2's rename test pass, the fake also needs a rekey helper. Update `FakeAccountRepository`:

```java
    // (add to the fake)
    @Override
    public Result<Account, AccountError> update(Account account) {
      // Real adapter handles rename via SQL; for the fake we treat update as "remove the old key
      // if any, install under the new key". When the rename test runs, the service first calls
      // update(originalAccount) (which is a no-op upsert) then save(renamed) (which inserts under
      // the new code) — but the old key is still in byCode. So strip it here:
      Account previous = byCode.put(account.code(), account);
      if (previous == null) {
        // No previous under this code — service may have called update on the not-yet-renamed
        // account. Tolerate it.
      }
      parents.clear();
      for (Account a : byCode.values()) {
        a.parentCode().ifPresent(parents::add);
      }
      return Result.success(account);
    }
```

(Simpler alternative: drop the rename SQL story in this slice — make rename a "create new + deactivate old + reactivate option" pattern. But the spec says rename is a primary use case; the SQL `UPDATE` path is the right primitive. Phase B's IT exercises the actual SQL behavior.)

**Update `AccountRepository` to expose the rename primitive explicitly.** Add to the interface (Task 7 retroactively):

```java
  /**
   * Atomic rename: change the natural key from {@code existing} to {@code newCode}. The adapter
   * issues a single SQL UPDATE; FK cascades on dependents.
   */
  Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode);
```

And to `AccountRepositoryAdapter`:

```java
  @Override
  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    if (jpa.existsById(newCode.value())) {
      return Result.failure(new AccountError.CodeInUseByPosting(newCode));
    }
    if (!jpa.existsById(existing.value())) {
      return Result.failure(new AccountError.NotFound(existing));
    }
    jpa.renameCode(existing.value(), newCode.value());
    return Result.success(
        AccountEntityMapper.toDomain(jpa.findById(newCode.value()).orElseThrow()));
  }
```

And to `JpaAccountRepository`:

```java
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      value = "UPDATE accounts SET code = :newCode WHERE code = :existing", nativeQuery = true)
  void renameCode(@org.springframework.data.repository.query.Param("existing") String existing,
                  @org.springframework.data.repository.query.Param("newCode") String newCode);
```

And finally update `AccountService.rename(...)` to call `repository.rename(existing, newCode)` instead of the convoluted update-then-resave path.

**Implementation order in this task:**

1. Add `rename` to `AccountRepository` and `AccountRepositoryAdapter` and `JpaAccountRepository`.
2. Update the fake to implement `rename`.
3. Rewrite `AccountService.rename(...)` to use the port's `rename`.

After those changes the service is clean and the IT (Task 16) gets a new test for the SQL-cascade rename.

- [ ] **Step 4: Run tests**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all green; AccountServiceTest is 8 tests.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(application): AccountService with CRUD + cycle detection

Eight use cases: create (with parent existence check), rename
(delegates to AccountRepository.rename which issues a native SQL
UPDATE so FK cascades), setParent (with cycle detection walking the
parent chain), deactivate/reactivate (idempotent), findByCode, findAll.

Eight TDD tests with an in-test FakeAccountRepository covering each
path including cycle detection across a three-level chain.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: Wire `AccountRepository`, update `PostJournalEntryService`, V4 migration

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`
- Create: `src/main/resources/db/migration/V4__postings_account_fk_and_seed.sql`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`

- [ ] **Step 1: Update `PostJournalEntryService` to do account lookup**

Replace the contents of `PostJournalEntryService.java`:

```java
package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.JournalValidationContext;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class PostJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final AccountRepository accountRepository;

  public PostJournalEntryService(
      JournalEntryRepository journalRepository, AccountRepository accountRepository) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
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
    JournalValidationContext ctx = new JournalValidationContext(accounts, nonLeafCodes);
    return JournalEntry.of(occurredOn, description, postings, ctx).map(journalRepository::save);
  }
}
```

- [ ] **Step 2: Update `PostJournalEntryServiceTest`**

The existing test uses a `FakeRepo` (JournalEntryRepository). Add a `FakeAccountRepository` mirroring the service test's fake. Update the test to construct `PostJournalEntryService` with both fakes. The existing tests that exercise `JournalError.Unbalanced` and `NoPostings` should pass an empty accounts fake (the validation context will be empty, which means the permissive path is engaged via the existing `JournalEntry.of(...)` 3-arg overload). Or — better — make the existing tests pass real Account fixtures.

Pragmatic path: keep the existing tests' fake accounts simple. Add a `seed(account)` helper to the fake. Update each test to seed `CASH` and `EQUITY` first.

Full updated test file in the same shape as before, but with the new fake injected. (See the existing `PostJournalEntryServiceTest` for the pattern; this is mechanical.)

- [ ] **Step 3: Write Flyway V4**

Create `src/main/resources/db/migration/V4__postings_account_fk_and_seed.sql`:

```sql
-- V3 is reserved for Slice 3's period table.

-- Seed minimal chart of accounts so existing tests pass before any application traffic.
INSERT INTO accounts (code, name, type, currency, parent_code, active) VALUES
    ('1000', 'Cash',                'ASSET',     'USD', NULL, TRUE),
    ('1100', 'Accounts Receivable', 'ASSET',     'USD', NULL, TRUE),
    ('3000', 'Owner Equity',        'EQUITY',    'USD', NULL, TRUE),
    ('4000', 'Revenue',             'REVENUE',   'USD', NULL, TRUE);

-- Tighten the existing postings.account_code into a real FK.
ALTER TABLE postings
    ADD CONSTRAINT postings_account_code_fk
    FOREIGN KEY (account_code) REFERENCES accounts(code) ON UPDATE CASCADE;
```

- [ ] **Step 4: Update `ApplicationConfig` to wire the new bean**

In `ApplicationConfig.java`, update the `postJournalEntryService` bean definition to take both dependencies:

```java
  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository, AccountRepository accountRepository) {
    return new PostJournalEntryService(journalRepository, accountRepository);
  }
```

(`AccountRepositoryAdapter` is annotated `@Repository`, so Spring will autoresolve it as the `AccountRepository` bean.)

- [ ] **Step 5: Run full verify**

```bash
./mvnw -B verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS. The `JpaJournalEntryRepositoryIT` still passes (its postings reference `1000` and `3000` which now exist via the V4 seed). The `ApplicationSmokeIT` also still passes for the same reason.

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/ docs/
git commit -m "$(cat <<'EOF'
feat(application): PostJournalEntryService does account lookup; V4 seeds CoA

PostJournalEntryService now extracts the unique AccountCodes from
postings, calls AccountRepository.findByCodeIn for a batch lookup,
plus AccountRepository.hasChildren per account to populate the
non-leaf set. The JournalValidationContext is built from those two
pieces and passed to JournalEntry.of(...).

Flyway V4 seeds a minimal chart of accounts (1000 Cash, 1100 AR, 3000
Equity, 4000 Revenue) so the existing JpaJournalEntryRepositoryIT
and ApplicationSmokeIT keep passing without test changes. Also adds
the FK postings.account_code → accounts.code with ON UPDATE CASCADE.

ApplicationConfig wires both AccountRepository and
JournalEntryRepository into PostJournalEntryService.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 19: Phase B acceptance — full verify

**Files:** none

- [ ] **Step 1: Clean cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -30
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Spot-check coverage**

JaCoCo should still report ≥85% line coverage on the bundle. PIT should still report ≥60% mutation on `domain.*` + `application.*`.

If PIT mutation drops below 60% because of the new account-validation paths in `JournalEntry.of(...)`, add tests to kill the surviving mutants — don't lower the threshold.

- [ ] **Step 3: No commit; Phase B is done**

Push the branch (the Phase B branch will be `13-slice-2-phase-b-wiring`) and open the PR.

---

## Phase B acceptance

~9 new commits on the branch (11 through 18). All gates green. Account JPA adapter + service + V4 seed live. `JournalEntry.of(ctx)` validates new entries against accounts. Existing tests + ITs untouched and still passing thanks to the seed.

---

# Phase C — Web layer + smoke

Phase C adds `AccountController`, the request/response DTOs, extends `ResultMapper`, updates `JournalEntryControllerTest` for the new failure paths, and extends `ApplicationSmokeIT` with the account flow.

---

## Task 20: Account DTOs

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/account/dto/CreateAccountRequest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/account/dto/UpdateAccountRequest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/account/dto/AccountResponse.java`

- [ ] **Step 1: `CreateAccountRequest`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Pattern(regexp = "^(ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE)$") String type,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @Size(max = 64) String parentCode) {

  @JsonCreator
  public CreateAccountRequest(
      @JsonProperty("code") String code,
      @JsonProperty("name") String name,
      @JsonProperty("type") String type,
      @JsonProperty("currency") String currency,
      @JsonProperty("parentCode") String parentCode) {
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
  }
}
```

- [ ] **Step 2: `UpdateAccountRequest`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(@Size(max = 64) String newCode, @Size(max = 64) String newParentCode) {}
```

(Both fields optional; null = no change. `newParentCode = ""` is interpreted as "clear parent" — see controller logic.)

- [ ] **Step 3: `AccountResponse`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;

public record AccountResponse(
    String code, String name, String type, String currency, String parentCode, boolean active) {

  public static AccountResponse of(Account a) {
    return new AccountResponse(
        a.code().value(),
        a.name(),
        a.type().name(),
        a.currency().getCurrencyCode(),
        a.parentCode().map(AccountCode::value).orElse(null),
        a.active());
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): request/response DTOs for accounts

CreateAccountRequest with Bean Validation (code/name non-blank+sized,
type pattern over the five enums, currency 3-letter, optional
parentCode). UpdateAccountRequest with two optional fields for rename
and re-parent. AccountResponse with an of(Account) factory.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 21: `AccountController` + MockMvc tests

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/account/AccountControllerTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/account/AccountController.java`

This task is similar in shape to Plan 2's `JournalEntryController` work: write the test against a `@MockitoBean AccountService`, write the controller to make it pass.

- [ ] **Step 1: Write tests covering all six endpoints + each failure variant**

(Full test file omitted here — follow the Plan 2 `JournalEntryControllerTest` pattern: `@WebMvcTest(AccountController.class)`, `@MockitoBean AccountService`, one test per success + per failure, MockMvc `.perform(post(...))` + `.andExpect(status()...)` + JSON path assertions.)

The endpoints to cover (per spec §6.1):
- `POST /accounts` → 201; failure 400 with `/problems/account/code-already-exists` or `/problems/account/parent-not-found`.
- `GET /accounts` → 200 with array.
- `GET /accounts/{code}` → 200 / 404 with `/problems/account/not-found`.
- `PATCH /accounts/{code}` (rename and/or re-parent) → 200 / 400 with `/problems/account/code-in-use-by-posting` or `cycle-would-be-created`.
- `POST /accounts/{code}/deactivate` → 200 (idempotent).
- `POST /accounts/{code}/reactivate` → 200 (idempotent).

- [ ] **Step 2: Implement `AccountController`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.account;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.AccountResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.CreateAccountRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.account.dto.UpdateAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<?> create(@Valid @RequestBody CreateAccountRequest req) {
    Optional<AccountCode> parent = Optional.ofNullable(req.parentCode()).map(AccountCode::new);
    Result<Account, AccountError> r =
        service.create(
            new AccountCode(req.code()),
            req.name(),
            AccountType.valueOf(req.type()),
            Currency.getInstance(req.currency()),
            parent);
    return r.fold(
        a ->
            ResponseEntity.created(URI.create("/accounts/" + a.code().value()))
                .body(AccountResponse.of(a)),
        this::error);
  }

  @GetMapping
  public List<AccountResponse> list() {
    return service.findAll().stream().map(AccountResponse::of).toList();
  }

  @GetMapping("/{code}")
  public ResponseEntity<?> get(@PathVariable String code) {
    return service
        .findByCode(new AccountCode(code))
        .<ResponseEntity<?>>map(a -> ResponseEntity.ok(AccountResponse.of(a)))
        .orElseGet(() -> error(new AccountError.NotFound(new AccountCode(code))));
  }

  @PatchMapping("/{code}")
  public ResponseEntity<?> update(@PathVariable String code, @Valid @RequestBody UpdateAccountRequest req) {
    AccountCode existing = new AccountCode(code);
    if (req.newCode() != null) {
      Result<Account, AccountError> r = service.rename(existing, new AccountCode(req.newCode()));
      if (r instanceof Result.Failure<Account, AccountError> f) {
        return error(f.error());
      }
      existing = new AccountCode(req.newCode()); // continue with new code for re-parent step
    }
    if (req.newParentCode() != null) {
      Optional<AccountCode> parent =
          req.newParentCode().isBlank()
              ? Optional.empty()
              : Optional.of(new AccountCode(req.newParentCode()));
      Result<Account, AccountError> r = service.setParent(existing, parent);
      if (r instanceof Result.Failure<Account, AccountError> f) {
        return error(f.error());
      }
    }
    return service
        .findByCode(existing)
        .<ResponseEntity<?>>map(a -> ResponseEntity.ok(AccountResponse.of(a)))
        .orElseGet(() -> error(new AccountError.NotFound(existing)));
  }

  @PostMapping("/{code}/deactivate")
  public ResponseEntity<?> deactivate(@PathVariable String code) {
    return service
        .deactivate(new AccountCode(code))
        .fold(a -> ResponseEntity.ok(AccountResponse.of(a)), this::error);
  }

  @PostMapping("/{code}/reactivate")
  public ResponseEntity<?> reactivate(@PathVariable String code) {
    return service
        .reactivate(new AccountCode(code))
        .fold(a -> ResponseEntity.ok(AccountResponse.of(a)), this::error);
  }

  private ResponseEntity<ProblemDetail> error(AccountError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
```

- [ ] **Step 3: Verify all tests pass**

```bash
./mvnw -B test -Dtest=AccountControllerTest 2>&1 | tail -10
```

Expected: all tests pass.

- [ ] **Step 4: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): AccountController with full CRUD endpoints

POST /accounts (create with optional parent), GET /accounts (flat list),
GET /accounts/{code} (single), PATCH /accounts/{code} (rename and/or
re-parent), POST /accounts/{code}/deactivate + reactivate.

All failure paths fold through ResultMapper → ProblemDetail
(application/problem+json). MockMvc tests cover every endpoint and
every failure variant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 22: Extend `ResultMapper` for `AccountError` and the new `JournalError` variants

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapperTest.java`

- [ ] **Step 1: Extend `ResultMapper.toProblemDetail` to handle `AccountError`**

Overload the existing `toProblemDetail(JournalError)` with a `toProblemDetail(AccountError)`:

```java
  public static ProblemDetail toProblemDetail(AccountError err) {
    return switch (err) {
      case AccountError.CodeAlreadyExists c -> problem(
          HttpStatus.BAD_REQUEST,
          "/account/code-already-exists",
          "Account code already exists",
          "An account with code '" + c.code().value() + "' already exists.");
      case AccountError.NotFound n -> problem(
          HttpStatus.NOT_FOUND,
          "/account/not-found",
          "Account not found",
          "No account with code '" + n.code().value() + "'.");
      case AccountError.ParentNotFound p -> problem(
          HttpStatus.BAD_REQUEST,
          "/account/parent-not-found",
          "Parent account not found",
          "No account with code '" + p.parentCode().value() + "' to set as parent.");
      case AccountError.CycleWouldBeCreated c -> problem(
          HttpStatus.BAD_REQUEST,
          "/account/cycle-would-be-created",
          "Account hierarchy would form a cycle",
          "Setting '"
              + c.child().value()
              + "' parent to '"
              + c.proposedParent().value()
              + "' would create a cycle.");
      case AccountError.CodeInUseByPosting u -> problem(
          HttpStatus.BAD_REQUEST,
          "/account/code-in-use-by-posting",
          "Account code already in use",
          "Code '" + u.code().value() + "' is already in use; pick a different code.");
    };
  }
```

Also extend the existing `toProblemDetail(JournalError)` sealed-switch to handle the four new variants:

```java
      case JournalError.AccountNotFound a -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/account-not-found",
          "Posting references an unknown account",
          "Account code '" + a.code().value() + "' does not exist.");
      case JournalError.AccountInactive a -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/account-inactive",
          "Posting references a deactivated account",
          "Account '" + a.code().value() + "' is not active.");
      case JournalError.AccountNotALeaf a -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/account-not-a-leaf",
          "Posting targets a non-leaf account",
          "Account '" + a.code().value() + "' has children; post to a leaf instead.");
      case JournalError.AccountCurrencyMismatch a -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/account-currency-mismatch",
          "Posting currency does not match account currency",
          "Account '"
              + a.code().value()
              + "' uses "
              + a.expectedByAccount().getCurrencyCode()
              + " but the posting amount uses "
              + a.actualOnPosting().getCurrencyCode()
              + ".");
```

(The existing `problem(...)` helper takes status, path, title, detail.)

- [ ] **Step 2: Extend `ResultMapperTest`**

Add nine tests — five for the AccountError variants, four for the new JournalError variants. Follow the existing patterns; assert title, status, type URI suffix, and that the detail mentions the offending code.

- [ ] **Step 3: Run + verify + commit**

```bash
./mvnw -B test 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): ResultMapper handles AccountError + 4 new JournalError variants

Sealed-switch on AccountError (5 variants → 5 ProblemDetails) and
extended sealed-switch on JournalError (4 new variants → 4 new
ProblemDetails). All under stable /problems/account/* and
/problems/journal/* type URIs.

Nine new ResultMapperTest cases.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 23: Extend `JournalEntryControllerTest` for the new failure paths

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

Append four new MockMvc tests — one per new `JournalError` variant: mock `PostJournalEntryService.post(...)` to return `Result.failure(...)` with each new variant, assert the 400 + ProblemDetail body.

- [ ] **Step 1: Add the tests + verify + commit**

Pattern is identical to the existing Unbalanced + validation-failure tests. (Full code omitted; the pattern is well-established in Plan 2's `JournalEntryControllerTest`.)

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(web): JournalEntryControllerTest covers 4 new account-failure paths

AccountNotFound, AccountInactive, AccountNotALeaf,
AccountCurrencyMismatch all assert 400 + application/problem+json +
the right type URI suffix. Tests mock PostJournalEntryService and
inject the Result.failure(...) variants directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 24: Extend `ApplicationSmokeIT`

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

Add a new test method or extend the existing one to:
1. Create a new account via `POST /accounts` with code `5000`, name `Office Supplies`, type `EXPENSE`, currency `USD`.
2. Post a journal entry that debits `5000` and credits `3000` — assert 201.
3. Post a journal entry that debits a never-created account (e.g. `9999`) — assert 400 + `/journal/account-not-found`.

- [ ] **Step 1: Extend + verify + commit**

```bash
./mvnw -B verify 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(smoke): ApplicationSmokeIT exercises account create + new failure path

Creates an EXPENSE account via POST /accounts, posts a balanced entry
against it (passes), then posts against an unknown account code
(asserts 400 + /problems/journal/account-not-found). Proves the
account+journal pipeline works end-to-end against real Postgres.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 25: README + CLAUDE.md updates + final cold-cache verify

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update README "Status"**

Flip Slice 2 status. (The README is currently structured around Plans, not Slices — adjust by adding a "Slices in progress" or similar small section.)

```markdown
## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton
- [x] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning
- [x] Slice 2 — chart of accounts (#13)
- [ ] Slice 3 — period model (#14)
```

- [ ] **Step 2: Update CLAUDE.md Key Conventions**

Add bullets for the new patterns:

```markdown
- **Account is the chart-of-accounts aggregate.** `AccountCode` (typed string) is the natural key — no surrogate UUID. Single currency per account. Hierarchy via `parentCode`. Leaf-only posting. See [ADR-0011](docs/adr/0011-account-hierarchy-leaf-only-posting.md).
- **Domain validation that needs external data takes a `JournalValidationContext`.** The service does the I/O (account lookups) and packs results into the record; `JournalEntry.of(...)` consumes plain values. See [ADR-0013](docs/adr/0013-journal-validation-context.md).
```

- [ ] **Step 3: Final cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -30
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: Slice 2 done — flip status, add account + validation-context conventions

README's Status section reflects Slice 2 closure (#13). CLAUDE.md
Key Conventions section gains a bullet for the Account aggregate +
the JournalValidationContext pattern.

Closes #13

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

~6 new commits (20 through 25). Account REST surface live; all failure paths render as ProblemDetails; smoke test exercises the full account+journal pipeline; README + CLAUDE.md updated; `./mvnw -B clean verify -Pmutation,openapi-gate` green.

PR title for Phase C: `Slice 2 Phase C: web + DTOs + ResultMapper extensions (closes #13)`.

On merge:
- Issue #13 closes.
- `main` has the full chart-of-accounts surface.
- Slice 3 (#14) plan kicks off next.

---

## Slice 2 overall acceptance

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green from a cold cache on every Phase PR and on `main` after each merge.
2. CI's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /accounts` creates a new account; `GET /accounts/{code}` reads it back.
4. `POST /journal-entries` against the seeded `1000`/`3000` accounts continues to return 201.
5. `POST /journal-entries` against an unknown account returns 400 + `/problems/journal/account-not-found`.
6. ADRs 0011 + 0013 committed; ADR README updated; `docs/openapi/openapi.yaml` regenerated (the new `/accounts` endpoints land in the snapshot).
7. Issue #13 closes when Phase C merges.
