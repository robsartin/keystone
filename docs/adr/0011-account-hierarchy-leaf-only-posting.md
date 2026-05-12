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
