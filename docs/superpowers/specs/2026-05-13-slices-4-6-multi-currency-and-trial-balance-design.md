# Slices 4 + 6 — Multi-Currency Journal Entries and Trial Balance

- **Date:** 2026-05-13
- **Status:** Approved
- **Author:** Rob Sartin (with Claude)
- **Closes-once-implemented:** [#17](https://github.com/robsartin/keystone/issues/17) (Slice 6), [#15](https://github.com/robsartin/keystone/issues/15) (Slice 4)

## 1. Context

Plans 1–3 and Slices 2–3 are on `main`. The keystone enforces a single
currency per journal entry and per account, posts entries via
`POST /journal-entries`, validates against accounts and period status,
emits Prometheus metrics, and is guarded by CI + a Repository Ruleset.

What it doesn't do yet:

- **Multi-currency.** Today the `MixedCurrencies` JournalError variant
  rejects entries that mix currencies. Real ledgers need cross-currency
  transactions — a USD→EUR transfer is a single entry with postings in
  two currencies.
- **Reporting.** No way to ask "what's the balance per account as of
  date X". Trial balance is the first read-side query the keystone
  needs.

This spec covers both because the trial-balance shape depends on the
multi-currency data model. Two implementation plans, two PR sequences;
Slice 6 ships first so Slice 4's SQL is built knowing about currency
as a dimension from day one.

## 2. Goals

- Drop the single-currency-per-entry rule. Each `Posting` carries a
  transaction-currency amount **and** a base-currency amount. The
  entry balances in **base currency**.
- A single global base currency is configured at deploy time via
  `keystone.base-currency: USD` (env override `KEYSTONE_BASE_CURRENCY`).
- Per-account currency stays single (from Slice 2). The transaction
  currency of every posting still must match its account's currency.
- The new `BaseCurrencyMismatch` JournalError variant fires when a
  posting's `baseAmount.currency()` doesn't match the configured base.
- Existing single-currency entries continue to work unchanged at the
  data level (the V5 migration backfills `base_minor_units` from
  `amount_minor_units`).
- `GET /reports/trial-balance` returns flat rows, one per
  `(account, currency)`, with both transaction-currency and
  base-currency totals.
- ADRs 0014 (multi-currency + base anchoring) and 0015 (trial balance
  shape) committed.

## 3. Non-goals (deferred to later slices)

- **Period-end revaluation.** Auto-generated FX gain/loss entries
  when closing a period — deferred. Slice 6 stores no FX rates; the
  client computes `baseMinorUnits` from whatever rate they used.
- **Server-side FX rate lookup.** No rate table, no external rate
  service. The client supplies both amounts.
- **Account hierarchy roll-up in trial balance.** Flat list for v1.
  Client builds the tree from `parentCode`. Server-side roll-up is
  a follow-up if real reporting use cases emerge.
- **Per-organization base currency.** Today's base is global; per-org
  bases become possible after Slice 5 (#16 tenancy) lands.
- **Closing snapshot trial balance.** Trial balance is *live* —
  includes everything posted ≤ `asOf` regardless of period close
  status. The Slice-3 period model gates *new postings*, not reports.

## 4. Multi-currency (Slice 6) — data model

### 4.1 `Posting` record

```java
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
            throw new IllegalArgumentException(
                    "baseAmount must be non-negative");
        }
    }
}
```

Both `Money` values are non-negative; sign is still carried by `Side`.
The constraint that `baseAmount.currency()` equals the configured base
currency lives in `JournalEntry.of(...)` (it needs the
`JournalValidationContext`, not just per-posting data).

### 4.1a `JournalEntry` record — `currency` field removed

Plan-1's `JournalEntry` record carries a single `Currency currency`
field as a per-entry annotation, because every posting in an entry
had to be in that currency. Post-Slice-6 the field is meaningless —
currency is per-posting. The field is **removed from the record**;
constructors update to take `(occurredOn, description, postings)`
only. The JPA reconstitute path (`new JournalEntry(...)` in the
mapper) loses one parameter; the mapper computes nothing from
currency. The Plan-2 `JournalEntryResponse` DTO also loses its
top-level `currency` field — see §4.6.

### 4.2 `JournalValidationContext` — gains `baseCurrency`

```java
public record JournalValidationContext(
        Map<AccountCode, Account> accounts,
        Set<AccountCode> nonLeafCodes,
        PeriodStatus periodStatus,
        Currency baseCurrency,        // NEW in Slice 6
        boolean permissiveMode) { ... }
```

The 2-arg back-compat constructor from Slice 2 keeps defaulting to
`OPEN/false`, and now also defaults `baseCurrency` to USD (the
keystone-era convention; tests can override). The `permissive()`
factory passes through.

### 4.3 `JournalError` changes

New variant:

```java
record BaseCurrencyMismatch(
        AccountCode code,
        Currency expectedByConfig,
        Currency actualOnPosting) implements JournalError {}
```

The `MixedCurrencies` variant **stays** on the sealed interface — read
paths that reconstitute pre-Slice-6 data through historical code
shouldn't break — but it is no longer emitted by `JournalEntry.of(...)`.
ADR-0014 notes the variant is retained for sealed-switch
exhaustiveness; `ResultMapper`'s handler maps it to a stable problem
URI (`/journal/mixed-currencies`) with a "this should be unreachable
under current rules" detail message. Removing the variant would be a
follow-up cleanup once the keystone is confident no historical
serialized form references it.

### 4.4 `JournalEntry.of(ctx)` — validation order

The full order with Slice 6 changes:

1. Postings non-empty *(existing)*
2. ~~Single currency on postings~~ — **removed**. Multi-currency is legal.
3. Period status is OPEN *(existing)*
4. Per-posting account validation: exists, active, leaf, **transaction-currency** matches `account.currency()` *(existing)*
5. **NEW: per-posting `baseAmount.currency()` matches `ctx.baseCurrency()`** — else `BaseCurrencyMismatch`
6. Base-amount sum doesn't overflow (per side) *(reuses `JournalError.Overflow`)*
7. **Balance check on base amounts**: `Σ debit baseAmount == Σ credit baseAmount` — else `JournalError.Unbalanced(debitsBase, creditsBase)`

Transaction-currency balance is no longer checked — that's the whole
point of the change.

### 4.5 Configuration

```yaml
# application.yaml
keystone:
  base-currency: USD
```

```java
@ConfigurationProperties("keystone")
public record KeystoneProperties(Currency baseCurrency) {
    public KeystoneProperties {
        if (baseCurrency == null) {
            baseCurrency = Currency.getInstance("USD");
        }
    }
}
```

`PostJournalEntryService` reads `properties.baseCurrency()` once at
construction time and packs it into every `JournalValidationContext`.

### 4.6 Wire format (breaking change)

```
POST /journal-entries
Content-Type: application/json

{
  "occurredOn": "2026-05-13",
  "description": "USD→EUR transfer at 0.92",
  "postings": [
    { "account": "1000-EUR", "side": "DEBIT",
      "minorUnits": 9200,  "currency": "EUR",
      "baseMinorUnits": 10000 },
    { "account": "1000-USD", "side": "CREDIT",
      "minorUnits": 10000, "currency": "USD",
      "baseMinorUnits": 10000 }
  ]
}
```

Three differences from pre-Slice-6:

1. **Top-level `currency` removed** (it was a single value for the
   whole entry; doesn't make sense post-Slice-6).
2. **Per-posting `currency` required**.
3. **Per-posting `baseMinorUnits` required**. For same-currency-as-base
   postings, clients send the same value as `minorUnits`.

The OpenAPI snapshot regenerates; `openapi-diff` Layer 4 flags this as
a breaking change. The Slice-6 Phase C PR carries the
`breaking-change-approved` label so the gate passes.

### 4.7 Rounding rule

Server-side FX math is **not** performed in Slice 6 — the client
supplies both transaction and base amounts. ADR-0014 documents
**`RoundingMode.HALF_EVEN` (banker's rounding)** as:

- The recommended client-side rule for any FX conversion they perform.
- The default any future server-side FX work (Slice 7 revaluation, FX
  rate lookups) will use.

The Money type stays integer-only (`minorUnits: long`); no
`BigDecimal` arithmetic at the domain level.

## 5. Trial balance (Slice 4) — read-only query

### 5.1 Endpoint

```
GET /reports/trial-balance?asOf=YYYY-MM-DD&includeZero=false
```

Both query params optional:

| Param | Default | Behavior |
|---|---|---|
| `asOf` | today (UTC) | Filters postings by `occurred_on ≤ asOf` |
| `includeZero` | `false` | Excludes accounts whose balance is zero |

Path validation: `asOf` must match `^\d{4}-\d{2}-\d{2}$`; malformed
input returns the existing `/problems/validation` ProblemDetail.

### 5.2 Response shape — flat rows

```json
[
  {
    "accountCode": "1000-EUR",
    "currency": "EUR",
    "debits": 9200,
    "credits": 0,
    "balance": 9200,
    "baseDebits": 10000,
    "baseCredits": 0,
    "baseBalance": 10000
  },
  {
    "accountCode": "1000-USD",
    "currency": "USD",
    "debits": 0,
    "credits": 10000,
    "balance": -10000,
    "baseDebits": 0,
    "baseCredits": 10000,
    "baseBalance": -10000
  }
]
```

One row per `(accountCode, currency)` pair with any postings ≤ `asOf`.

- `debits` / `credits` / `balance` are in **transaction currency**
  (`currency` column).
- `baseDebits` / `baseCredits` / `baseBalance` are in the **base**
  currency from `keystone.base-currency`.
- `balance` is `debits - credits` (signed); same for `baseBalance`.
- Sorted by `accountCode` ASC. Stable, predictable.

### 5.3 SQL

```sql
SELECT p.account_code,
       p.currency,
       SUM(CASE WHEN p.side='DEBIT'  THEN p.amount_minor_units  ELSE 0 END) AS debits,
       SUM(CASE WHEN p.side='CREDIT' THEN p.amount_minor_units  ELSE 0 END) AS credits,
       SUM(CASE WHEN p.side='DEBIT'  THEN p.base_minor_units    ELSE 0 END) AS base_debits,
       SUM(CASE WHEN p.side='CREDIT' THEN p.base_minor_units    ELSE 0 END) AS base_credits
FROM   postings p
JOIN   journal_entries je ON je.id = p.journal_entry_id
WHERE  je.occurred_on <= :asOf
GROUP  BY p.account_code, p.currency
HAVING (CASE WHEN :includeZero THEN TRUE
             ELSE (SUM(CASE WHEN p.side='DEBIT'  THEN p.amount_minor_units ELSE 0 END)
                 - SUM(CASE WHEN p.side='CREDIT' THEN p.amount_minor_units ELSE 0 END)) <> 0
        END)
ORDER  BY p.account_code, p.currency;
```

A single GROUP BY query; no temp tables, no recursive CTE. With the
index on `(occurred_on)` and the existing FK index on
`postings.account_code`, this is fast for keystone-era scale.

### 5.4 Service + controller

```
domain/        — no new types (trial balance is a read-only projection,
                 not an aggregate)
application/reports/
  TrialBalanceService.java  — calls a port `TrialBalanceReadModel`
                              and returns the list of rows
infrastructure/persistence/reports/
  TrialBalanceReadModel.java       (port interface, lives under
                                    `domain/reports/`)
  TrialBalanceJdbcReadModel.java   (adapter; uses Spring Data JDBC or
                                    `JdbcTemplate`)
infrastructure/web/reports/
  TrialBalanceController.java
  dto/TrialBalanceRowResponse.java
```

`TrialBalanceReadModel` lives in `domain/reports/` and is a port —
the adapter does SQL; the service composes. ArchUnit's hexagonal rules
already cover this layering.

Why JDBC and not JPA? Because the row shape doesn't map to any entity;
a hand-written SQL query against postings + a row mapper is simpler
than a custom JPA projection. Spring Boot already wires
`JdbcTemplate`.

## 6. Persistence

### 6.1 Flyway V5 — postings gain currency + base_minor_units

```sql
-- V5__postings_multi_currency.sql

-- Per-posting currency moves from journal_entries to postings.
ALTER TABLE postings ADD COLUMN currency CHAR(3);
UPDATE postings p
   SET currency = (SELECT currency FROM journal_entries WHERE id = p.journal_entry_id);
ALTER TABLE postings ALTER COLUMN currency SET NOT NULL;

-- Base-currency amount. Pre-Slice-6 entries were single-currency,
-- so base equals transaction.
ALTER TABLE postings ADD COLUMN base_minor_units BIGINT;
UPDATE postings SET base_minor_units = amount_minor_units;
ALTER TABLE postings ALTER COLUMN base_minor_units SET NOT NULL;
ALTER TABLE postings ADD CONSTRAINT postings_base_minor_units_nonneg
    CHECK (base_minor_units >= 0);

-- journal_entries.currency becomes redundant.
ALTER TABLE journal_entries DROP COLUMN currency;
```

### 6.2 No new migration for Slice 4

Slice 4 is read-only; reuses V5's schema.

## 7. Application + service surfaces

### 7.1 `PostJournalEntryService` (updated)

```java
public final class PostJournalEntryService {

    private final JournalEntryRepository journalRepository;
    private final AccountRepository accountRepository;
    private final PeriodService periodService;
    private final Currency baseCurrency;   // NEW: from KeystoneProperties

    public PostJournalEntryService(
            JournalEntryRepository journalRepository,
            AccountRepository accountRepository,
            PeriodService periodService,
            Currency baseCurrency) {
        // ... null checks
    }

    public Result<PersistedJournalEntry, JournalError> post(
            LocalDate occurredOn, String description, List<Posting> postings) {
        // ... existing account + period lookups
        JournalValidationContext ctx = new JournalValidationContext(
                accounts, nonLeafCodes, periodStatus, baseCurrency, false);
        return JournalEntry.of(occurredOn, description, postings, ctx)
                .map(journalRepository::save);
    }
}
```

`ApplicationConfig` wires `baseCurrency` from `KeystoneProperties`.

### 7.2 `TrialBalanceService` (new)

```java
public final class TrialBalanceService {

    private final TrialBalanceReadModel readModel;

    public TrialBalanceService(TrialBalanceReadModel readModel) { ... }

    public List<TrialBalanceRow> query(LocalDate asOf, boolean includeZero) {
        return readModel.fetch(asOf, includeZero);
    }
}
```

`TrialBalanceRow` is a record in `domain/reports/`:

```java
public record TrialBalanceRow(
        AccountCode accountCode,
        Currency currency,
        long debits,
        long credits,
        long baseDebits,
        long baseCredits) {

    public long balance() {
        return Math.subtractExact(debits, credits);
    }

    public long baseBalance() {
        return Math.subtractExact(baseDebits, baseCredits);
    }
}
```

Balance computations use `Math.subtractExact` to fail loud on
overflow.

## 8. Web layer

### 8.1 Slice 6 — updated DTOs

- `PostJournalEntryRequest`: top-level `currency` field removed.
- `PostingRequest`: gains `currency` (`@Pattern("^[A-Z]{3}$")`) and
  `baseMinorUnits` (`@PositiveOrZero`).
- `JournalEntryResponse`: top-level `currency` removed.
- `PostingResponse`: gains `currency` and `baseMinorUnits`.

### 8.2 Slice 6 — `ResultMapper` extension

One new variant handled:

```java
case JournalError.BaseCurrencyMismatch m -> problem(
        HttpStatus.BAD_REQUEST,
        "/journal/base-currency-mismatch",
        "Posting baseAmount currency does not match the configured base",
        "Account '" + m.code().value() + "' baseAmount is in "
                + m.actualOnPosting().getCurrencyCode()
                + " but the configured base is "
                + m.expectedByConfig().getCurrencyCode() + ".");
```

The `MixedCurrencies` handler stays on the sealed switch (defensive —
never fires from current `of()`); maps to a stable problem URI for
the (impossible) case where stored data reconstitutes through code
that still emits it.

### 8.3 Slice 4 — new controller

```java
@RestController
@RequestMapping("/reports")
@Validated
public class TrialBalanceController {

    @GetMapping("/trial-balance")
    public List<TrialBalanceRowResponse> get(
            @RequestParam(value = "asOf", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(value = "includeZero", required = false, defaultValue = "false")
                boolean includeZero) {
        LocalDate effective = (asOf != null) ? asOf : LocalDate.now(ZoneOffset.UTC);
        return service.query(effective, includeZero).stream()
                .map(TrialBalanceRowResponse::of)
                .toList();
    }
}
```

## 9. Testing strategy

### 9.1 Slice 6 — domain + application

- `PostingTest` updated for the new `baseAmount` field + invariants.
- `JournalEntryTest`: tests for the new `BaseCurrencyMismatch` path
  and the new base-amount balance rule. Existing `MixedCurrencies`
  test is **removed** (no longer fires from `of(ctx)`).
- `JournalValidationContextTest`: covers the new `baseCurrency` field.
- `PostJournalEntryServiceTest`: extended with a fake configuration to
  exercise base currency.
- `JournalEntryEntityMapperTest`: covers the new column round-trip.

### 9.2 Slice 6 — persistence

- `JpaJournalEntryRepositoryIT`: extended with a multi-currency
  round-trip test (USD→EUR transfer; verify both `currency` and
  `base_minor_units` per posting).

### 9.3 Slice 6 — web

- `JournalEntryControllerTest`: updated for the new request shape
  (per-posting currency + baseMinorUnits); test for
  `BaseCurrencyMismatch`.
- `ResultMapperTest`: one new test for the new variant.

### 9.4 Slice 6 — smoke

`ApplicationSmokeIT` extended with a multi-currency entry: post a
USD→EUR transfer, verify 201, verify both postings persist with
correct currency + base amounts.

### 9.5 Slice 4 — application + persistence

- `TrialBalanceServiceTest` with a fake `TrialBalanceReadModel` —
  asserts pass-through; the service is thin.
- `TrialBalanceJdbcReadModelIT` (Testcontainers Postgres): the meat.
  Seed entries, run the query, assert row shape + filtering
  (`asOf`, `includeZero`).

### 9.6 Slice 4 — web

- `TrialBalanceControllerTest`: MockMvc tests for the 200 path,
  default-asOf behavior, malformed-asOf 400 (validation), empty
  result, and `includeZero=true`.

### 9.7 Slice 4 — smoke

`ApplicationSmokeIT` extended: post a balanced entry, hit
`GET /reports/trial-balance`, assert two rows (debit + credit) sum
to zero in base.

### 9.8 Coverage + mutation

JaCoCo ≥ 85% line, PIT ≥ 60% mutation on `domain..` + `application..`.

## 10. Migration of existing data

Pre-Slice-6 production state is `main`'s current data (seeded accounts
1000, 1100, 3000, 4000 — all USD; the JpaJournalEntryRepositoryIT and
ApplicationSmokeIT have small test data). V5 backfills
`base_minor_units` from `amount_minor_units` and moves `currency` from
`journal_entries` to `postings`. After V5 runs, existing entries:

- Have `postings.currency = 'USD'` (cascaded from
  `journal_entries.currency` which was always USD).
- Have `postings.base_minor_units = postings.amount_minor_units`.
- Validate cleanly against the new `of(ctx)` (single-USD entries are
  trivially "balanced in base").

No code-level migration; the V5 migration is purely SQL.

## 11. ADRs

| # | Title | Slice |
|---|---|---|
| 0014 | Multi-currency journal entries with base-currency anchoring; HALF_EVEN rounding convention | 6 |
| 0015 | Trial balance reporting — flat rows, live, by `(account, currency)` | 4 |

ADR-0014 is the substantial one; ADR-0015 is a shorter "we chose flat
over grouped, and live over period-respecting" record.

## 12. Sequencing

Two plans, two PR sequences, in order:

**Slice 6 plan** (multi-currency, three phases):

- Phase A: domain types + ADR-0014 (purely additive — `Posting`
  becomes 4-arg via a new constructor that defaults the old 3-arg path
  to a flag; or a single breaking-change commit followed by everything
  recompiles cleanly).
- Phase B: wire `JournalEntry.of` + `PostJournalEntryService` +
  V5 migration + JPA + IT updates.
- Phase C: web + DTO breaking change + OpenAPI regen with
  `breaking-change-approved` label + smoke + closes #17.

**Slice 4 plan** (trial balance, three phases):

- Phase A: domain + ADR-0015 (the `TrialBalanceRow` record +
  `TrialBalanceReadModel` port).
- Phase B: JDBC adapter + IT + service + tests.
- Phase C: web + DTO + smoke + closes #15.

Slice 6 lands first; Slice 4 builds on the V5 schema.

## 13. Acceptance criteria (overall)

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every
   Phase PR (Slice 6 Phase C uses
   `-Dopenapi.diff.skip=false -Dbreaking-change-approved=true` or
   the equivalent label flow).
2. CI's `docker` job continues to publish
   `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /journal-entries` accepts a multi-currency entry that
   balances in base. Same-currency-as-base entries continue to work
   with `baseMinorUnits` equal to `minorUnits`.
4. `POST /journal-entries` rejects an entry where any posting's
   `baseMinorUnits` doesn't sum to balance across the entry.
5. `GET /reports/trial-balance` returns flat rows sorted by
   `accountCode`; default `asOf` = today; non-zero accounts only by
   default.
6. The committed `docs/openapi/openapi.yaml` reflects the new wire
   shape after Slice 6 Phase C and the new endpoint after Slice 4
   Phase C.
7. ADRs 0014 + 0015 committed; ADR README updated.
8. Issue #17 closes when Slice 6 Phase C merges; #15 closes when
   Slice 4 Phase C merges.

## 14. Open questions / follow-ups for later slices

- **Slice 7 (next pair-end):** FX rate table + period-end revaluation
  entries. Will add a `fx_rates` table keyed by `(from, to, valid_on)`,
  a `RevaluationService` that generates entries at period close, and
  ADR-0016.
- **Slice 5 (tenancy):** when it lands, promote `keystone.base-currency`
  from a global config to an `Organization.baseCurrency` field. Will
  require updating `JournalValidationContext` construction to look up
  the tenant's base.
- **Server-side FX rate lookup** during posting (so clients don't
  have to compute `baseMinorUnits` themselves) — possible enhancement
  in or after Slice 7.
- **Hierarchy roll-up in trial balance** — add a
  `?rollup=true&level=N` flag once a real client needs it.
- **CSV/Excel export** — separate endpoint (`Accept: text/csv` or
  `/reports/trial-balance.csv`), follow-up.
