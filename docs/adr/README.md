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
| [0005](0005-postgres-flyway.md) | Postgres + Flyway from day one | Accepted |
| [0006](0006-openapi-gates.md) | OpenAPI four-layer gate | Accepted |
| [0007](0007-junit-jupiter-6.md) | JUnit Jupiter 6 with ArchUnit on the JUnit Platform | Accepted |
| [0008](0008-observability.md) | Micrometer + structured JSON logs + MDC correlation IDs | Accepted |
| [0009](0009-trunk-based-development.md) | Trunk-based development with squash-merged PRs and required CI | Accepted |
| [0010](0010-journal-entry-id-wrapper.md) | JournalEntryId wrapper with UUID v7; PersistedJournalEntry separates intent from persisted state | Accepted |
| [0011](0011-account-hierarchy-leaf-only-posting.md) | Account aggregate with hierarchy and leaf-only posting | Accepted |
| [0012](0012-period-model-sequential-close.md) | Period model — fixed monthly, sequential close, audit fields | Accepted |
| [0013](0013-journal-validation-context.md) | Domain validation that needs external data uses a context record | Accepted |
| [0014](0014-multi-currency-base-anchoring.md) | Multi-currency journal entries with base-currency anchoring | Accepted |
| [0015](0015-no-url-versioning.md) | No URL versioning | Accepted |
| [0016](0016-multi-tenant-row-level-isolation.md) | Multi-tenant row-level isolation with Postgres RLS | Accepted |

## Numbering

ADRs are numbered sequentially. The next free number is the smallest integer N
such that no `ADR-N` file exists and N does not appear in a "Reserved" row above.

- Reservations exist when an ADR is planned for an upcoming PR/plan but not yet
  written. Update this table in the same commit that writes the reserved ADR.
- See [`0000-template.md`](0000-template.md) for the starting structure.
