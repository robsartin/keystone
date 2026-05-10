# Architecture Decision Records

Each ADR captures one significant architectural decision in
[Michael Nygard's format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
(Context / Decision / Consequences). ADRs are immutable once Accepted; to
revise, write a new ADR that supersedes the old.

See [ADR-0001](0001-record-decisions-in-adrs.md) for the meta-decision.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-record-decisions-in-adrs.md) | Record significant decisions in ADRs | Accepted |
| [0002](0002-hexagonal-architecture.md) | Hexagonal architecture enforced by ArchUnit | Accepted |
| [0003](0003-money-as-integer-minor-units.md) | Money as integer minor units, ISO 4217 via java.util.Currency | Accepted |
| [0004](0004-result-type-and-problem-details.md) | Result type for internal APIs; ProblemDetails at HTTP boundary | Accepted |
| 0005 | (reserved — Postgres + Flyway, lands in Plan 2 Phase B) | Reserved |
| 0006 | (reserved — OpenAPI four-layer gate, lands in Plan 2 Phase E) | Reserved |
| [0007](0007-junit-jupiter-6.md) | JUnit Jupiter 6 with ArchUnit on the JUnit Platform | Accepted |
| 0008 | (reserved — Micrometer + structured JSON logs + MDC, lands in Plan 2 Phase D) | Reserved |
| 0009 | (reserved — trunk-based dev + signed commits + squash-only, lands in Plan 3) | Reserved |
| [0010](0010-journal-entry-id-wrapper.md) | JournalEntryId wrapper with UUID v7; PersistedJournalEntry separates intent from persisted state | Accepted |

## Numbering

- Sequential. The next free number is the smallest integer N such that no
  ADR-N exists and N is not in the "Reserved" rows above.
- Reservations exist when an ADR is planned for an upcoming PR/plan but
  not yet written. Update this table the same commit you write the
  reserved ADR.
- See [`0000-template.md`](0000-template.md) for the starting structure.
