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
