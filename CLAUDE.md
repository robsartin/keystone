# CLAUDE.md

Conventions and AI-specific workflow for keystone. For full setup, see
[README.md](README.md).

## Quick reference

- `./mvnw -B verify` — full local gate (format, lint, test, coverage, mutation, arch rules)
- `./mvnw spotless:apply` — auto-format Java
- `./mvnw test` — unit tests only

## Architecture rules (ArchUnit-enforced)

Hexagonal architecture per [ADR-0002](docs/adr/0002-hexagonal-architecture.md).
Dependencies point inward; never outward.

- `domain/` — pure POJOs; imports nothing outside `java.*` and own packages
- `application/` — depends on `domain` only
- `infrastructure/` — depends on `domain` + `application` (added in Plan 2)

## Key conventions

- **Money is integers**, never `double` or `BigDecimal`. ISO 4217 via `java.util.Currency`. See [ADR-0003](docs/adr/0003-money-as-integer-minor-units.md).
- **Internal APIs return `Result<T, E>`**, not exceptions. Exceptions are reserved for true bugs. See [ADR-0004](docs/adr/0004-result-type-and-problem-details.md).
- **TDD always**: red → green → refactor → commit. Each commit shows the discipline.
- **Tests use `@DisplayName`** and method names of the form `should<Expected>When<Condition>`.
- **JaCoCo gate at 85% line coverage**; PIT gate at 60% mutation on `domain..` + `application..`.

## Code style

- Google Java Format via Spotless (run `./mvnw spotless:apply`)
- Checkstyle: 750-line file max, 30-line method max, no star imports, braces required
