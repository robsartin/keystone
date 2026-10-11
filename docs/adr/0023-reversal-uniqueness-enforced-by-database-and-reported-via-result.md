# ADR-0023: Reversal uniqueness enforced by the database and reported through the port's Result

- **Status:** Accepted
- **Date:** 2026-10-11

## Context

"An entry can be reversed at most once" was enforced only by an application
check (`ReverseJournalEntryService`: `existsReversalOf` then `saveReversal`)
spanning separate transactions. Two near-simultaneous requests can both pass
the check and both insert, double-posting the correction (issue #81).

Two questions followed from closing that gap:

1. What backs the invariant? A check-then-act race can only be closed by an
   atomic arbiter, i.e. the database.
2. Where is the resulting constraint violation turned into the domain's
   `JournalError.AlreadyReversed`? The violation surfaces as Spring's
   `DataIntegrityViolationException`, and the application layer may not depend
   on Spring (ADR-0002, enforced by `HexagonalArchitectureTest`).

## Decision

- A partial unique index on `(tenant_id, reverses_id) WHERE reverses_id IS NOT
  NULL` (migration `V11`) is the source of truth for the invariant. The
  service-level `existsReversalOf` check stays as the fast, friendly path.
- The persistence adapter translates the violation of that index into a
  domain failure. `JournalEntryRepository.saveReversal` returns
  `Result<PersistedJournalEntry, JournalError>` (ADR-0004), and the JPA
  adapter returns `JournalError.AlreadyReversed` when, and only when, the
  violated constraint is the reversal index. Any other integrity violation
  is a bug and is rethrown.
- The adapter's `saveReversal` is not itself `@Transactional`; the write
  runs in a short `TransactionTemplate` transaction and the exception is
  caught outside it. Catching inside a transactional method leaves the
  transaction rollback-only and the caller sees
  `UnexpectedRollbackException`.

The code (`V11__*.sql`, `JpaJournalEntryRepository`) is the authority for the
index name and the translation; this ADR does not restate them. A
deterministic two-thread test in `JpaJournalEntryRepositoryIT` proves the
behaviour at the port and through `ReverseJournalEntryService`.

## Alternatives considered

- **Service catches the runtime exception** (the first option in #81).
  Rejected: the application layer would have to import
  `DataIntegrityViolationException`, breaking ADR-0002 and the ArchUnit rule
  that keeps `..application..` free of Spring. Catching a generic
  `RuntimeException` instead would avoid the import but would also swallow
  genuine bugs and cannot tell this constraint from any other.
- **Adapter throws a port-level domain exception** (e.g. a
  `DuplicateReversalException` in `domain`) that the service catches.
  Rejected: it keeps Spring out, but it models an expected business outcome
  as an exception, against ADR-0004, and the failure would be invisible in
  the port's type signature.
- **Lock the original row (`SELECT ... FOR UPDATE`) instead of an index.**
  Rejected: it serialises reversals pessimistically and still leaves the
  invariant unenforced for any writer that skips the lock; the index holds
  for every path, including manual SQL.
- **Catch inside the `@Transactional` adapter method.** Rejected: the
  transaction is already rollback-only after the violation, so the commit
  throws `UnexpectedRollbackException` even though the failure was handled.

## Consequences

- The invariant now holds under concurrency and for any writer.
- A port signature change: every `JournalEntryRepository` implementation
  (including test fakes) returns `Result` from `saveReversal`.
- The loser of a race gets the same `AlreadyReversed` outcome (mapped by
  `ResultMapper`) as a sequential duplicate, not a 500.
- The adapter depends on the index name staying in step with the migration;
  renaming the index requires updating the adapter and its integration test,
  which fails loudly if they drift.
