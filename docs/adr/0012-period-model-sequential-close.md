# ADR-0012: Period model — fixed monthly, sequential close, audit fields

- **Status:** Accepted
- **Date:** 2026-05-12

## Context

Slice 3 introduces a period model. Real ledgers close periods after
the books are balanced, and once closed, postings into that period are
forbidden — otherwise the closed snapshot becomes a moving target.

The foundation design spec (§4.3, §5.2, §6.2) picked the simplest
shape that's still useful: calendar-month periods, sequential close,
re-open allowed for the most-recently-closed period.

## Decision

- **`Period` is keyed by `java.time.YearMonth`** (`2026-05` etc.).
  Calendar months, no fiscal-year config, no period-creation UI. Most
  months never have a row in the table — open is implicit. A row
  appears only when the period's status changes (i.e., when closed).
- **Period status:** `OPEN` or `CLOSED`.
- **Audit fields:** `closedAt`, `closedBy`, `reopenedAt`, `reopenedBy`.
  Until Slice 5 wires up auth, `closedBy`/`reopenedBy` default to
  `"system"`.
- **Sequential close:** `close(X)` is allowed only when every earlier
  YearMonth with at least one posting is already closed. Failing the
  rule returns `PeriodError.NotSequentiallyClosable(attempted,
  earliestOpenActive)`.
- **Re-open allowed for the most-recently-closed period only.**
  Failing returns `PeriodError.NotMostRecentlyClosed(attempted,
  latestClosed)`.
- **`PostJournalEntryService` looks up the period status** for the
  entry's `YearMonth` and packs it into the `JournalValidationContext`.
  `JournalEntry.of(...)` rejects with
  `JournalError.PostingInClosedPeriod(YearMonth)` when the status is
  `CLOSED`. No override flag — adjustments land via reopen → post →
  reclose, leaving an auditable trail.

## Consequences

- The set of periods is bounded by the set of months with at least
  one posting plus the explicitly-closed months — never grows
  unboundedly.
- Sequential close requires a small extra method on
  `JournalEntryRepository` (`distinctOccurredMonths()`) so
  `PeriodService` can compute "earliest open with postings" without
  crossing the journal aggregate boundary directly.
- Adjustment entries are visible in `git log`-like fashion: the period
  row gains a `reopenedAt`/`reopenedBy` audit pair, then later a new
  `closedAt`/`closedBy` pair when re-closed.
- We accept that calendar-month periods don't handle non-standard
  fiscal years. That's a Slice-N concern if and when it arises.
- We accept that closing doesn't snapshot a trial balance. Slice 4's
  TB query works against the live ledger; immutable snapshots would
  be a separate decision.
