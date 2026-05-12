# CLAUDE.md

Conventions and AI-specific workflow for keystone. For full setup, see
[README.md](README.md).

## Quick reference

- `./mvnw -B verify` — fast local gate (Spotless, Checkstyle, tests, coverage, ArchUnit). PIT and OpenAPI lint skipped.
- `./mvnw -B verify -Pmutation` — adds PIT mutation coverage (≥60% on `domain..` + `application..`).
- `./mvnw -B verify -Popenapi-gate` — adds Spectral lint + snapshot diff + openapi-diff vs main.
- `./mvnw -B verify -Popenapi-update` — regenerates `docs/openapi/openapi.yaml` from the running app (use after intentional API change, then commit).
- `./mvnw spring-boot:run` — run the app (requires Postgres on `localhost:5434`).
- `docker compose up -d --build` — bring up the full local stack (Postgres + app + Prometheus + Grafana). Dashboard at http://localhost:3000.
- `docker compose down -v` — tear it all down including volumes.
- `./mvnw spotless:apply` — auto-format Java.

## Architecture rules (ArchUnit-enforced)

Hexagonal architecture per [ADR-0002](docs/adr/0002-hexagonal-architecture.md).
Dependencies point inward; never outward.

- `domain/` — pure POJOs; imports nothing outside `java.*` and own packages
- `application/` — depends on `domain` only
- `infrastructure/` — depends on `domain` + `application`; further split into:
  - `infrastructure.persistence.*` — JPA adapters
  - `infrastructure.web.*` — `@RestController`s + DTOs + `ResultMapper`
  - `infrastructure.observability.*` — Micrometer + Logback config
  - `infrastructure.shared.*` — utilities (e.g., `UuidV7Generator`)
  - `infrastructure.config.*` — Spring `@Configuration`

## Key conventions

- **Money is integers**, never `double` or `BigDecimal`. ISO 4217 via `java.util.Currency`. See [ADR-0003](docs/adr/0003-money-as-integer-minor-units.md).
- **Account is the chart-of-accounts aggregate.** `AccountCode` (typed string) is the natural key — no surrogate UUID. Single currency per account. Hierarchy via `parentCode`. Leaf-only posting. See [ADR-0011](docs/adr/0011-account-hierarchy-leaf-only-posting.md).
- **Domain validation that needs external data takes a `JournalValidationContext`.** The service does the I/O (account lookups) and packs results into the record; `JournalEntry.of(...)` consumes plain values. See [ADR-0013](docs/adr/0013-journal-validation-context.md).
- **Internal APIs return `Result<T, E>`**, not exceptions. Exceptions are reserved for true bugs. See [ADR-0004](docs/adr/0004-result-type-and-problem-details.md). At the HTTP boundary, `ResultMapper` translates `JournalError` to RFC 9457 `ProblemDetail`.
- **Identifiers are typed.** `JournalEntryId(UUID value)` wraps a UUID v7. `PersistedJournalEntry(id, entry)` distinguishes saved-in-storage from constructed-but-unsaved. See [ADR-0010](docs/adr/0010-journal-entry-id-wrapper.md).
- **Persistence is real Postgres + Flyway** from day one. No H2. Tests use Testcontainers. See [ADR-0005](docs/adr/0005-postgres-flyway.md).
- **TDD always**: red → green → refactor → commit.
- **Tests use `@DisplayName`** and method names `should<Expected>When<Condition>`.
- **JaCoCo gate at 85% line coverage**; PIT gate at 60% mutation on `domain..` + `application..`.
- **OpenAPI is a committed snapshot.** Any controller change that affects the API surface must be paired with a regenerated `docs/openapi/openapi.yaml`. CI fails the build on snapshot drift. See [ADR-0006](docs/adr/0006-openapi-gates.md).
- **Observability**: structured JSON logs (Logstash encoder) in production, readable console pattern locally. Every request carries a correlation ID in MDC + `X-Correlation-Id` response header. Custom Prometheus metrics live in `infrastructure.observability.MetricsConfig`. See [ADR-0008](docs/adr/0008-observability.md).

## Code style

- Google Java Format via Spotless (`./mvnw spotless:apply`)
- Checkstyle: 750-line file max, 30-line method max, no star imports, braces required
