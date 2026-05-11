# Slices 2 + 3 — Chart of Accounts and Period Model

- **Date:** 2026-05-11
- **Status:** Approved
- **Author:** Rob Sartin (with Claude)
- **Closes-once-implemented:** [#13](https://github.com/robsartin/keystone/issues/13) (Slice 2), [#14](https://github.com/robsartin/keystone/issues/14) (Slice 3)

## 1. Context

Plans 1, 2, and 3 of the keystone foundation are complete. The walking
skeleton accepts balanced double-entry `POST /journal-entries` requests,
persists them to Postgres, exposes RFC 9457 ProblemDetails on validation
failures, emits Prometheus metrics, runs full CI on every PR, and is
guarded by a Repository Ruleset enforcing trunk-based development with
signed commits.

What the keystone *doesn't* have yet is a real ledger model. Postings
reference accounts via the `AccountCode` placeholder — a typed string
with no semantics. There's no chart of accounts, so any code is
acceptable. There's no period model, so entries can be backdated to any
date with no friction. The keystone explicitly deferred both of these
to Slice 2 and Slice 3 in the foundation design spec.

This spec covers both slices together because their validation pipelines
share the `JournalEntry.of(...)` factory and they're tightly coupled —
period validation requires real `occurredOn` semantics, which only make
sense once accounts have real types and currencies. The two slices ship
as separate implementation plans and separate PRs (Slice 2 first;
Slice 3 layers on top), but their design is one document.

## 2. Goals

- Replace the placeholder `AccountCode` with a real `Account` aggregate
  carrying `type`, `currency`, optional `parentCode` for hierarchy, and
  an `active` flag.
- `JournalEntry.of(...)` validates each posting against the account
  registry: account exists, is active, is a leaf (no children), and has
  a currency matching the posting amount.
- Add a `Period` aggregate keyed by `java.time.YearMonth`. Periods can
  be open or closed. Closing is sequential (must close earlier periods
  first). Reopening is allowed for the most-recently-closed period and
  is auditable.
- `JournalEntry.of(...)` rejects entries whose `occurredOn` falls in a
  closed period.
- New REST endpoints under `/accounts` (full CRUD minus DELETE) and
  `/periods` (close + reopen lifecycle).
- All new failure modes surface as RFC 9457 ProblemDetails with stable
  type URIs.
- ADRs 0011 (Account hierarchy + leaf-only posting), 0012 (period model
  + sequential close), and 0013 (validation-context pattern) land
  alongside the implementation.
- Existing ITs (`JpaJournalEntryRepositoryIT`, `ApplicationSmokeIT`,
  `JournalEntryControllerTest`) continue to pass after a small
  Flyway-seeded chart of accounts.

## 3. Non-goals (deferred)

- **Multi-currency journal entries** — single-currency-per-entry stays;
  multi-currency revaluation is Slice 6 (#17).
- **Trial balance and other reports** — Slice 4 (#15).
- **Tenancy and authentication** — `closedBy`/`reopenedBy` default to
  `"system"` until Slice 5 (#16) wires up OAuth2.
- **Closing-snapshot trial balance** — closing locks; it does not
  compute a TB snapshot. If immutable closing snapshots become
  necessary, that's its own future ADR.
- **Account deletion** — never. Soft-delete via `deactivate`; hard
  delete would orphan postings. Typo fixes happen via `PATCH` rename.
- **Hierarchical roll-up reporting** — the data shape supports it
  (parent_code FK), but the queries land in Slice 4.

## 4. Architecture

### 4.1 Domain types

```
domain/
├── account/                           ← new package
│   ├── AccountCode.java               (unchanged from Plan 1)
│   ├── AccountType.java               new enum: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE; .normalSide()
│   ├── NormalSide.java                new enum: DEBIT, CREDIT
│   ├── Account.java                   new record (see §4.2)
│   ├── AccountError.java              new sealed: CodeAlreadyExists, NotFound, ParentNotFound, CycleWouldBeCreated, CodeInUseByPosting
│   └── AccountRepository.java         new port
├── period/                            ← new package
│   ├── PeriodStatus.java              new enum: OPEN, CLOSED
│   ├── Period.java                    new record (see §4.3)
│   ├── PeriodError.java               new sealed: NotSequentiallyClosable(attempted, earliestOpen), NotMostRecentlyClosed(attempted, latestClosed), NotFound
│   └── PeriodRepository.java          new port
└── journal/
    ├── JournalEntry.java              of(...) signature gains a JournalValidationContext parameter
    ├── JournalError.java              + AccountNotFound, AccountInactive, AccountNotALeaf, AccountCurrencyMismatch, PostingInClosedPeriod
    └── JournalValidationContext.java  new record: Map<AccountCode, Account>, PeriodStatus
```

The `account` and `period` packages are siblings of `journal`. ArchUnit
gains a rule that `account` and `period` may not depend on each other —
they collaborate only via `journal.JournalValidationContext`.

### 4.2 `Account` record

```java
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

`AccountCode` is the natural primary key (user-supplied at create-time,
not minted). No surrogate UUID — different from `JournalEntry`, which
needed UUID v7 for time-sortable surrogate keys; charts of accounts get
user-meaningful codes like `1000` and `4200`.

Cycle detection (parent-of-parent eventually leads back to self) is
done in `AccountService.setParent`, not in the record constructor — it
requires a repository lookup.

### 4.3 `Period` record

```java
public record Period(
        YearMonth yearMonth,
        PeriodStatus status,
        Optional<Instant> closedAt,
        Optional<String> closedBy,
        Optional<Instant> reopenedAt,
        Optional<String> reopenedBy) {

    public Period {
        Objects.requireNonNull(yearMonth, "yearMonth");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(closedBy, "closedBy");
        Objects.requireNonNull(reopenedAt, "reopenedAt");
        Objects.requireNonNull(reopenedBy, "reopenedBy");
        if (status == PeriodStatus.CLOSED && (closedAt.isEmpty() || closedBy.isEmpty())) {
            throw new IllegalArgumentException("CLOSED period must have closedAt and closedBy");
        }
    }

    public static Period openFor(YearMonth yearMonth) {
        return new Period(yearMonth, PeriodStatus.OPEN,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
```

Most months never have a `Period` row. The `PeriodService.findByYearMonth(...)`
synthesizes `Period.openFor(...)` when no row exists — every month is
implicitly open until explicitly closed.

### 4.4 `JournalValidationContext`

```java
public record JournalValidationContext(
        Map<AccountCode, Account> accounts,
        PeriodStatus periodStatus) {

    public JournalValidationContext {
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(periodStatus, "periodStatus");
        accounts = Map.copyOf(accounts);
    }
}
```

`accounts` is a defensively-copied unmodifiable map of *only the
accounts referenced by the postings in the request* — the service does
the lookup via `AccountRepository.findByCodeIn(Set<AccountCode>)` and
passes the result. Callers don't need every account in the chart.

`periodStatus` is the status for the entry's `YearMonth` — either
`OPEN` (when no period row exists or the row says OPEN) or `CLOSED`.

ADR-0013 captures this pattern.

### 4.5 Updated `JournalEntry.of(...)`

New signature:

```java
public static Result<JournalEntry, JournalError> of(
        LocalDate occurredOn,
        String description,
        List<Posting> postings,
        JournalValidationContext ctx)
```

Order of checks (short-circuits on first failure):

1. **Postings non-empty** — returns `JournalError.NoPostings` *(existing)*
2. **Single currency on postings** — returns `JournalError.MixedCurrencies(currencies)` *(existing)*
3. **Period status is OPEN** — else `JournalError.PostingInClosedPeriod(YearMonth.from(occurredOn))` *(NEW)*
4. **Per-posting account validation, in order** *(NEW)*:
   - account exists in `ctx.accounts` → else `AccountNotFound(code)`
   - `account.active` is true → else `AccountInactive(code)`
   - `account` has no children (must be a leaf) → else `AccountNotALeaf(code)`
   - `account.currency` equals `posting.amount.currency` → else `AccountCurrencyMismatch(code, expected, actual)`
5. **Posting sums don't overflow** *(existing)*
6. **Balanced (debits == credits)** *(existing)*

Step 4's "no children" check uses an additional field on `Account`
populated by the repository at fetch time (a transient `boolean isLeaf`
or similar). Cleaner alternative: the service determines leaf-ness
upfront via a separate query and packs it into the context. Either
works; pick during Slice 2 implementation.

## 5. Application-layer services

### 5.1 `AccountService`

```java
public interface AccountService {
    Result<Account, AccountError> create(AccountCode code, String name, AccountType type,
                                          Currency currency, Optional<AccountCode> parentCode);
    Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode);
    Result<Account, AccountError> setParent(AccountCode code, Optional<AccountCode> newParentCode);
    Result<Account, AccountError> deactivate(AccountCode code);  // idempotent
    Result<Account, AccountError> reactivate(AccountCode code);  // idempotent
    Optional<Account> findByCode(AccountCode code);
    List<Account> findAll();
}
```

Cycle detection in `setParent`: walk up the parent chain from
`newParentCode`; if `code` appears, return `CycleWouldBeCreated`. Loop
bounded by the chain depth (at most O(n) where n is chart depth, ~4 in
practice).

Rename when the new code is in use by an existing account returns
`CodeAlreadyExists`. Postings referencing the old code follow the
rename via `ON UPDATE CASCADE` on the FK.

### 5.2 `PeriodService`

```java
public interface PeriodService {
    Result<Period, PeriodError> close(YearMonth yearMonth, String actor);
    Result<Period, PeriodError> reopen(YearMonth yearMonth, String actor);
    Period findByYearMonth(YearMonth yearMonth);  // synthesizes OPEN if no row
    List<Period> findAllClosed();                  // for the /periods?status=closed list
}
```

Sequential close: when `close(yearMonth, ...)` is called, find the
earliest `YearMonth` with status `OPEN` *and* with at least one journal
entry recorded against it (i.e., the smallest YearMonth that has a
posting AND no `Period` row marking it CLOSED). If `yearMonth` is
greater than that earliest open, return `NotSequentiallyClosable`.

Mirror logic for reopen: only the most-recently-closed period can be
reopened.

`actor` is `"system"` until Slice 5 lands auth.

### 5.3 Updated `PostJournalEntryService`

```java
public Result<PersistedJournalEntry, JournalError> post(
        LocalDate occurredOn, String description, List<Posting> postings) {

    Set<AccountCode> codes = postings.stream()
            .map(Posting::account)
            .collect(Collectors.toSet());
    Map<AccountCode, Account> accounts = accountRepository.findByCodeIn(codes);
    PeriodStatus periodStatus = periodService.findByYearMonth(YearMonth.from(occurredOn)).status();

    JournalValidationContext ctx = new JournalValidationContext(accounts, periodStatus);

    return JournalEntry.of(occurredOn, description, postings, ctx)
            .map(journalEntryRepository::save);
}
```

I/O lives in the service (lookups). Domain stays I/O-free.

## 6. API surface

### 6.1 Accounts (Slice 2)

| Method + Path | Request | Response |
|---|---|---|
| `POST /accounts` | `CreateAccountRequest` | `201 + AccountResponse` / `400 + ProblemDetail` |
| `GET /accounts` | — | `200 + [AccountResponse]` (flat list; client builds tree) |
| `GET /accounts/{code}` | — | `200 + AccountResponse` / `404 + ProblemDetail` |
| `PATCH /accounts/{code}` | `UpdateAccountRequest` (rename and/or re-parent) | `200 + AccountResponse` / `400/404 + ProblemDetail` |
| `POST /accounts/{code}/deactivate` | empty | `200 + AccountResponse` (idempotent) |
| `POST /accounts/{code}/reactivate` | empty | `200 + AccountResponse` (idempotent) |

Request DTOs:

```json
// CreateAccountRequest
{ "code": "1000", "name": "Cash", "type": "ASSET", "currency": "USD", "parentCode": null }

// UpdateAccountRequest
{ "newCode": "1001", "newParentCode": "1000" }   // either field optional; null = no change
```

### 6.2 Periods (Slice 3)

| Method + Path | Request | Response |
|---|---|---|
| `GET /periods?status=closed` | — | `200 + [PeriodResponse]` (closed periods only; open is implicit) |
| `GET /periods/{yyyy-mm}` | — | `200 + PeriodResponse` (synthesizes OPEN if no row) |
| `POST /periods/{yyyy-mm}/close` | empty | `200 + PeriodResponse` / `400 + ProblemDetail` |
| `POST /periods/{yyyy-mm}/reopen` | empty | `200 + PeriodResponse` / `400 + ProblemDetail` |

`{yyyy-mm}` matches `^\d{4}-\d{2}$`. Bean Validation rejects malformed
paths with the existing `/problems/validation` ProblemDetail.

### 6.3 Updated `POST /journal-entries`

Same wire format. New possible failure responses:

| Type URI | Trigger |
|---|---|
| `/problems/journal/account-not-found` | A posting references an unknown `AccountCode` |
| `/problems/journal/account-inactive` | Account exists but is deactivated |
| `/problems/journal/account-not-a-leaf` | Account has child accounts (it's a roll-up) |
| `/problems/journal/account-currency-mismatch` | Posting amount's currency ≠ account's currency |
| `/problems/journal/posting-in-closed-period` | `occurredOn`'s `YearMonth` has been closed |

`detail` strings include the offending `AccountCode` / `YearMonth` so
clients can report precisely.

`ResultMapper` extends to handle the new `JournalError` variants;
sealed-switch exhaustiveness ensures Slice 2 / Slice 3 implementations
can't merge without handling them.

### 6.4 New ProblemDetail type URIs (account + period endpoints)

| Type URI | Trigger |
|---|---|
| `/problems/account/code-already-exists` | `POST /accounts` with an existing code |
| `/problems/account/not-found` | `GET/PATCH /accounts/{code}` for unknown code |
| `/problems/account/parent-not-found` | `parentCode` references an unknown account |
| `/problems/account/cycle-would-be-created` | `setParent` would create a cycle |
| `/problems/account/code-in-use-by-posting` | Rename to a code already in use |
| `/problems/period/not-sequentially-closable` | Close request out of order |
| `/problems/period/not-most-recently-closed` | Reopen request not on most-recent-closed |
| `/problems/period/not-found` | (rare; period synthesizing covers most cases) |

## 7. Persistence

### 7.1 Flyway V2 — accounts

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

### 7.2 Flyway V3 — periods

```sql
CREATE TABLE periods (
    year_month    CHAR(7)     PRIMARY KEY,
    status        VARCHAR(8)  NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    closed_at     TIMESTAMPTZ,
    closed_by     VARCHAR(200),
    reopened_at   TIMESTAMPTZ,
    reopened_by   VARCHAR(200),
    CONSTRAINT periods_yearmonth_format CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT periods_closed_has_metadata CHECK (
        status = 'OPEN'
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
    )
);
```

### 7.3 Flyway V4 — postings FK to accounts + seed minimal CoA

```sql
-- Tighten the existing postings.account_code into a real FK now that accounts exist.
ALTER TABLE postings
    ADD CONSTRAINT postings_account_code_fk
    FOREIGN KEY (account_code) REFERENCES accounts(code) ON UPDATE CASCADE;

-- Minimal seed chart of accounts so existing tests pass without reseeding.
INSERT INTO accounts (code, name, type, currency, parent_code, active) VALUES
    ('1000', 'Cash',                'ASSET',     'USD', NULL, TRUE),
    ('1100', 'Accounts Receivable', 'ASSET',     'USD', NULL, TRUE),
    ('3000', 'Owner Equity',        'EQUITY',    'USD', NULL, TRUE),
    ('4000', 'Revenue',             'REVENUE',   'USD', NULL, TRUE);
```

V4 lands in **Slice 2** (the FK belongs with the accounts table; the
seed is what makes existing tests still work). V3 lands in **Slice 3**
(period table only; no posting changes).

## 8. Testing strategy

### 8.1 New unit-test files

- `AccountTest` — record validation (no blank name, no self-parent, parent ≠ self).
- `AccountTypeTest` — `.normalSide()` returns the right side for all five enums.
- `AccountErrorTest` — sealed-interface exhaustiveness check (one test that pattern-matches all variants).
- `PeriodTest` — record validation (`CLOSED` requires `closedAt + closedBy`; `openFor(...)` factory).
- `PeriodStatusTest` — trivial.
- `JournalValidationContextTest` — defensive copy, null rejection.
- `JournalEntryTest` — extended with five new test methods, one per new `JournalError` variant.

### 8.2 New application-layer tests (with fakes)

- `AccountServiceTest` — every method, including cycle-detection edge cases.
- `PeriodServiceTest` — sequential close, sequential reopen, idempotent close.
- `PostJournalEntryServiceTest` — extended: existing tests now construct a `JournalValidationContext` via fakes.

### 8.3 New integration tests (Testcontainers Postgres)

- `JpaAccountRepositoryIT` — round-trip, batch lookup (`findByCodeIn`), parent FK cascade on rename, hierarchy queries.
- `JpaPeriodRepositoryIT` — round-trip, status transitions persist correctly.

### 8.4 Web tests (MockMvc)

- `AccountControllerTest` — every endpoint, success + each failure variant.
- `PeriodControllerTest` — every endpoint, success + each failure variant.
- `JournalEntryControllerTest` — extended with the five new failure variants.

### 8.5 Smoke

`ApplicationSmokeIT` — extended to (1) create a posting against the
seeded `1000` and `3000` accounts and verify success, (2) create a
posting against an unknown account and verify the 400, (3) close
`2026-05`, attempt to post a 2026-05 entry, verify the 400.

### 8.6 Coverage + mutation

JaCoCo line ≥ 85% on the bundle (existing). PIT ≥ 60% on
`domain..` + `application..` (existing).

### 8.7 ArchUnit additions

```java
noClasses().that().resideInAPackage("..domain.account..")
    .should().dependOnClassesThat().resideInAPackage("..domain.period..");

noClasses().that().resideInAPackage("..domain.period..")
    .should().dependOnClassesThat().resideInAPackage("..domain.account..");
```

(Slice 2 lands the first; Slice 3 lands the second when `period`
exists.)

## 9. Migration of existing data

Pre-Slices-2/3 production state has zero rows in `accounts` and
`periods` (these tables don't exist). The Flyway V2/V3/V4 migrations
create the tables and seed a minimal chart of accounts. Existing
postings reference `1000` and `3000`, which the V4 seed inserts.

If a real production deployment had postings referencing accounts not
in the seed, the V4 migration would fail at the FK-add step. For
keystone-era this isn't a concern (the only "production" is local dev
+ CI).

## 10. Audit fields until Slice 5

`closedBy` and `reopenedBy` need values before authentication exists.
Default to **`"system"`** (string literal). When Slice 5 lands OAuth2,
these fields take the authenticated principal's identifier.

`AccountService` doesn't track `createdBy`/`updatedBy` in Slices 2/3 —
it's deferred to Slice 5 as well.

## 11. ADRs landed

| # | Title | Slice |
|---|---|---|
| 0011 | Account aggregate with hierarchy and leaf-only posting | 2 |
| 0012 | Period model: fixed monthly + sequential close | 3 |
| 0013 | JournalValidationContext: domain-pure validation needing external data | 2 |

ADR-0013 is fundamental enough that it lands with Slice 2 and Slice 3
references it.

## 12. Sequencing

Two implementation plans, two PRs (likely each split into Phase A/B/C
PRs as Plans 1-3 were):

**Slice 2 (#13) — Account aggregate** — lands the `account` package,
the `JournalValidationContext`, the V2 + V4 migrations, the
`AccountService` and `AccountController`, the four account-related
`JournalError` variants, the `JpaAccountRepository`, and the seed
chart of accounts that keeps existing tests passing. ADR-0011 +
ADR-0013.

**Slice 3 (#14) — Period model** — lands the `period` package, the
V3 migration, the `PeriodService` and `PeriodController`, the
`PostingInClosedPeriod` `JournalError` variant, the `JpaPeriodRepository`,
and extends `PostJournalEntryService` to do the period lookup.
ADR-0012.

Each plan splits into phases in the same shape as Plan 2:
- Phase A: domain types + ADR
- Phase B: application + persistence + IT
- Phase C: web + DTOs + ResultMapper extension + smoke

## 13. Acceptance criteria (overall)

1. `./mvnw -B clean verify -Pmutation,openapi-gate` is green on every
   PR and on every push to `main`.
2. The CI workflow's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest`
   on each push to `main`.
3. The Repository Ruleset's `build` check requirement is satisfied by
   the existing `build` job (no workflow-name changes).
4. `POST /accounts` creates an account; `POST /journal-entries` against
   that account succeeds; `POST /journal-entries` against an unknown
   account returns `400 + /problems/journal/account-not-found`.
5. `POST /periods/2026-05/close` followed by a `POST /journal-entries`
   with `occurredOn: 2026-05-15` returns `400 + /problems/journal/posting-in-closed-period`;
   `POST /periods/2026-05/reopen` followed by the same request
   succeeds.
6. ADRs 0011, 0012, 0013 are committed.
7. The keystone overview Grafana dashboard continues to populate
   correctly.

## 14. Open questions / follow-ups for later slices

- **Trial balance reporting** — Slice 4 (#15). Will likely query
  postings JOIN accounts GROUP BY type, with hierarchy roll-up.
- **Closing snapshot** — defer until there's a concrete need (e.g.,
  audit, financial-statement immutability). Today's "close" locks; it
  doesn't snapshot.
- **Account dimensions** (department, project, cost center) — defer
  until a real use case appears. Adding nullable columns later is
  cheap.
- **Audit fields backfill** when Slice 5 lands — the `"system"` default
  becomes the historical-baseline value; new entries get the real
  principal.
- **Period-close performance** — sequential close requires finding the
  earliest open `YearMonth` with postings. At keystone scale this is a
  trivial query; if it ever gets slow, an index on `postings.occurred_on`
  helps.
