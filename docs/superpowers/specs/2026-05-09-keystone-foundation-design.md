# Keystone — Foundation Design

- **Date:** 2026-05-09
- **Status:** Approved
- **Author:** Rob Sartin (with Claude)
- **Repo:** `robsartin/keystone` (public, Apache 2.0)
- **Local path:** `~/code/keystone`
- **Group / package:** `co.embracejoy.accounting.keystone`

## 1. Context

`keystone` is the foundation of a new general ledger built in Spring Boot. The
goal of *this* spec is **not** to design the full ledger; it is to plant a
keystone that:

1. Proves the entire build / test / observability / deploy wiring works
   end-to-end with a single thin vertical slice (the walking skeleton).
2. Is shaped such that the next several slices grow naturally into a real
   double-entry general ledger without rework.

Treat the slice in this spec as a *seed*. Subsequent slices (chart of
accounts, period model, trial balance, multi-tenancy, auth) each get their
own brainstorm → spec → plan cycle.

## 2. Goals

- Spring Boot 4.0.x on Java 25, packaged as a runnable jar and a Docker image.
- Hexagonal architecture, enforced at build time by ArchUnit.
- Test-driven development for every line of production code in the keystone
  slice. JUnit Jupiter 6 where the toolchain supports it; ArchUnit run via the
  JUnit Platform launcher alongside Jupiter 6.
- Custom Prometheus metrics scraped by a local Prometheus, visualised in a
  pre-provisioned Grafana dashboard.
- Structured JSON logs via SLF4J + Logback + `logstash-logback-encoder` with
  MDC correlation IDs.
- Postgres + Flyway from day 1; Testcontainers in tests.
- A `Result<T, E>` sealed type for all internal API surfaces; RFC 9457
  ProblemDetails at the HTTP boundary.
- ADRs land in the same PR as the code that justifies them.
- Quality gates fail the PR build (compile, unit, integration, ArchUnit,
  JaCoCo ≥85% line, PIT ≥60% mutation, Checkstyle, Spotless, OWASP
  dependency-check, four OpenAPI gates).

## 3. Non-goals (deferred to later slices)

- A real chart of accounts with account types, hierarchy, normal side, etc.
- Multi-currency revaluation. The keystone rejects mixed-currency entries
  outright.
- Period model, period close, trial balance, financial reports.
- Authentication / authorization. Endpoints are unauthenticated in the
  keystone phase.
- Multi-tenancy.
- Event sourcing or any messaging story.

## 4. Architecture

### 4.1 Hexagonal layers

Dependencies point inward. The compile-time graph:

```
infrastructure  →  application  →  domain
   (web, JPA,        (use-case
    config,           services
    metrics)          orchestrating
                      domain ports)
```

- `domain` imports nothing outside `java.*` and its own sub-packages. No
  Spring, no JPA, no Jackson, no web, no SLF4J types as fields.
- `application` depends on `domain` only. It orchestrates domain via the
  ports declared in `domain`.
- `infrastructure` may depend on `domain` and `application`. It implements
  the ports (driven adapters: JPA, in-memory) and exposes the application
  (driving adapters: HTTP controllers).

The reason infrastructure imports domain — apparent paradox — is the
Dependency Inversion Principle: the **abstraction** (the port interface)
lives in domain, and the **implementation** (the adapter) lives in
infrastructure. The adapter must `implements` the port, which requires an
import. The arrow that matters is the *abstraction* arrow, which points
inward; the *file-level import* arrow points inward as well. ADR-0002
captures this with a worked example so future readers do not re-litigate it.

### 4.2 Package layout

```
co.embracejoy.accounting.keystone
├── KeystoneApplication
├── domain
│   ├── shared
│   │   └── Result
│   ├── money
│   │   ├── Money
│   │   └── MoneyError
│   ├── account
│   │   └── AccountCode
│   └── journal
│       ├── JournalEntry
│       ├── Posting
│       ├── Side                      (enum: DEBIT, CREDIT)
│       ├── JournalError              (sealed)
│       └── JournalEntryRepository    (port)
├── application
│   └── journal
│       └── PostJournalEntryService
└── infrastructure
    ├── persistence
    │   └── journal
    │       ├── JpaJournalEntryRepository    (adapter)
    │       ├── JournalEntryEntity
    │       └── PostingEntity
    ├── web
    │   ├── JournalEntryController
    │   ├── ResultMapper                     (Result<_, DomainError> → ResponseEntity / ProblemDetail)
    │   └── dto
    │       ├── PostJournalEntryRequest
    │       └── JournalEntryResponse
    ├── observability
    │   └── MetricsConfig                    (custom counter + timer beans)
    └── config
        └── (cross-cutting Spring config)
```

### 4.3 ArchUnit rules

A single test class `HexagonalArchitectureTest` enforces:

- `domain` may not depend on `..application..` or `..infrastructure..`.
- `application` may not depend on `..infrastructure..`.
- No class outside `domain.shared` may declare a public method whose return
  type is a `Throwable`. Domain failures are values, not exceptions.
- Classes in `..web..` may not import from `jakarta.persistence..` or
  `..infrastructure.persistence..`.
- No class in `domain..` may import `org.springframework..`,
  `jakarta.persistence..`, `com.fasterxml.jackson..`, or `org.slf4j..` as a
  field type.
- All ports (interfaces declared in `domain`) must be implemented by exactly
  one class outside test sources.

## 5. Walking-skeleton vertical slice

### 5.1 User story

> *As an integrator, I can `POST /journal-entries` with a balanced set of
> debits and credits, and on success the entry is persisted, a structured
> log line is emitted, and the `keystone_journal_entries_posted_total`
> counter is incremented.*

### 5.2 Wire format

Request:

```json
POST /journal-entries
{
  "occurredOn": "2026-05-09",
  "description": "Opening balance",
  "currency": "USD",
  "postings": [
    { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
    { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
  ]
}
```

Success — `201 Created`, `Location: /journal-entries/{id}`:

```json
{
  "id": "01J...",
  "occurredOn": "2026-05-09",
  "description": "Opening balance",
  "currency": "USD",
  "postings": [...]
}
```

Failure — `400 Bad Request` with RFC 9457 ProblemDetails:

```json
{
  "type": "https://embracejoy.co/problems/journal/unbalanced",
  "title": "Journal entry is not balanced",
  "status": 400,
  "detail": "Sum of debits (10000 USD) does not equal sum of credits (9000 USD)",
  "instance": "/journal-entries"
}
```

### 5.3 TDD order

Each step is RED → GREEN → REFACTOR. Each row is a single commit (or two,
red then green) so the discipline is visible in `git log`.

| # | Test class | Behaviour |
|---|---|---|
| 1 | `MoneyTest` | construction, equality, `plus`/`minus` returning `Result`, currency mismatch produces `Failure(CurrencyMismatch)`, overflow via `addExact` produces `Failure(Overflow)` |
| 2 | `PostingTest` | `Posting(AccountCode, Side, Money)`; rejects zero or negative amounts |
| 3 | `JournalEntryTest` | `JournalEntry.of(...)` returns `Failure(NoPostings)`, `Failure(MixedCurrencies)`, `Failure(Unbalanced)`, or `Success` |
| 4 | `PostJournalEntryServiceTest` | service calls port; on `Success` persists and returns `Success`; on `Failure` returns it untouched |
| 5 | `JournalEntryControllerTest` | MockMvc; 201 + Location on Success; 400 + ProblemDetails on each failure variant |
| 6 | `HexagonalArchitectureTest` | layer rules above |
| 7 | `JpaJournalEntryRepositoryIT` | Testcontainers Postgres + Flyway V1 migration; persists and re-reads a JournalEntry |
| 8 | `OpenApiSnapshotTest` | loads `target/openapi.yaml` (generated by `springdoc-openapi-maven-plugin`) and diffs against `docs/openapi/openapi.yaml` |
| 9 | `ApplicationSmokeIT` | full `@SpringBootTest`, real Testcontainers Postgres, POST a balanced entry, assert 201, then GET `/actuator/prometheus` and assert `keystone_journal_entries_posted_total{result="ok"} >= 1` |

### 5.4 Core type sketches

```java
public sealed interface Result<T, E> {
    record Success<T, E>(T value) implements Result<T, E> {}
    record Failure<T, E>(E error) implements Result<T, E> {}

    static <T, E> Result<T, E> success(T v) { return new Success<>(v); }
    static <T, E> Result<T, E> failure(E e) { return new Failure<>(e); }

    <U> Result<U, E> map(Function<? super T, ? extends U> f);
    <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> f);
    <R> R fold(Function<? super T, ? extends R> ok,
               Function<? super E, ? extends R> err);
}

public record Money(long minorUnits, Currency currency) {
    public Money { Objects.requireNonNull(currency); }
    public Result<Money, MoneyError> plus(Money other);   // CurrencyMismatch | Overflow
    public Result<Money, MoneyError> minus(Money other);
    public Money negate();
    public boolean isZero();
}

public sealed interface MoneyError {
    record CurrencyMismatch(Currency expected, Currency actual) implements MoneyError {}
    record Overflow() implements MoneyError {}
}

public sealed interface JournalError {
    record NoPostings() implements JournalError {}
    record MixedCurrencies(Set<Currency> currencies) implements JournalError {}
    record Unbalanced(Money debits, Money credits) implements JournalError {}
}
```

## 6. ADRs landed with the keystone

| # | Title |
|---|---|
| 0001 | Record decisions in ADRs (the meta-ADR) |
| 0002 | Hexagonal architecture enforced by ArchUnit (with worked example explaining inward-pointing dependencies) |
| 0003 | Money as integer minor units, ISO 4217 via `java.util.Currency` |
| 0004 | Result type for internal APIs; RFC 9457 ProblemDetails at HTTP boundary |
| 0005 | Postgres + Flyway from day 1; Testcontainers in tests |
| 0006 | OpenAPI gates: generate → Spectral lint → snapshot diff → breaking-change diff |
| 0007 | JUnit Jupiter 6; ArchUnit via JUnit Platform launcher |
| 0008 | Micrometer → Prometheus, structured JSON logs (logstash-logback-encoder), MDC correlation IDs |
| 0009 | Trunk-based development with required PR + signed commits + squash-only merges |

ADRs use the Nygard format (Context / Decision / Consequences) and live in
`docs/adr/`.

## 7. Build & quality gates

Single Maven build. `./mvnw -B verify` runs the full gate, in order:

1. `spotless:check` — Google Java Format.
2. `checkstyle:check` — 750-line file, 30-line method, no star imports,
   braces required.
3. Compile (Java 25).
4. `surefire:test` — unit tests with JUnit Jupiter 6.
5. `failsafe:integration-test` — `*IT` classes; Testcontainers Postgres;
   includes ArchUnit, the OpenAPI snapshot test, and `ApplicationSmokeIT`.
6. `jacoco:report` and `jacoco:check` — fail under 85% line coverage on
   production code (excluding generated, DTOs, Spring `@Configuration`).
7. `pitest:mutationCoverage` — fail under 60% mutation score on
   `domain..` and `application..` only.
8. `springdoc-openapi-maven-plugin` — boots app, dumps `target/openapi.yaml`.
9. Spectral lint of `target/openapi.yaml` against `.spectral.yaml` ruleset
   (operationId, summary, 4xx response, $ref-only schemas, ProblemDetails on
   errors, etc.).
10. Snapshot diff: `diff -u docs/openapi/openapi.yaml target/openapi.yaml`.
    Non-empty → fail with a message instructing the developer to run
    `./mvnw -Popenapi-update verify` and commit the regenerated file.
11. `openapi-diff` between `origin/main:docs/openapi/openapi.yaml` and the
    PR's `docs/openapi/openapi.yaml`. Breaking changes fail unless the PR
    carries the label `breaking-change-approved` (the CI job reads the label
    via `gh pr view --json labels` when running on a `pull_request` event).
    Skipped on `push` to `main` (no base ref to diff against). Steps 8–11
    are bound to a Maven profile `openapi-gate` activated in CI; locally
    they can be skipped with `-P!openapi-gate` for inner-loop speed.
12. `dependency-check-maven` — fail on HIGH/CRITICAL CVEs not listed in
    `dependency-check-suppressions.xml`.

`./mvnw -Popenapi-update verify` overwrites `docs/openapi/openapi.yaml` from
`target/openapi.yaml` and skips the snapshot-diff and breaking-change steps.

## 8. Observability

- `spring-boot-starter-actuator` exposes `/actuator/health`,
  `/actuator/info`, `/actuator/prometheus` (others disabled).
- `micrometer-registry-prometheus` registers the Prometheus registry.
- Custom meters in `MetricsConfig`:
  - `keystone_journal_entries_posted_total{result="ok"|"invalid"}`
  - `keystone_journal_entries_post_duration_seconds` (Timer)
- Logback configured via `logback-spring.xml`. Production profile uses
  `logstash-logback-encoder`'s `LogstashEncoder` (one JSON object per line).
  Local profile uses a colored pattern encoder for readability.
- An MDC servlet filter ensures every request has a `correlationId` (read
  from `X-Correlation-Id` if present, else generated). MDC keys: `traceId`,
  `spanId`, `correlationId`. The filter logs the request with these fields.
- `docker-compose.yml` brings up:
  - `postgres:16` on `localhost:5434`, db `keystone`, user/password
    `keystone`/`keystone`.
  - `prom/prometheus:latest` on `localhost:9090`, scraping
    `host.docker.internal:8080/actuator/prometheus`.
  - `grafana/grafana:latest` on `localhost:3000`, with the Prometheus
    datasource and a "Keystone Overview" dashboard provisioned from
    `grafana/provisioning/`.
- Dashboard panels: HTTP RPS, p50/p95/p99 latency, JVM heap, JVM GC pause,
  journal-entry-posted rate split by `result`, Postgres connection pool
  in-use vs. idle.

## 9. CI workflow (GitHub Actions)

Single workflow `.github/workflows/ci.yml`, triggered on `pull_request` and
`push` to `main`. Jobs:

- **build** — Ubuntu, JDK 25 (Temurin), Maven cache, runs `./mvnw -B verify`
  (which itself includes everything in §7). Uploads JaCoCo HTML and
  `target/openapi.yaml` as artifacts.
- **docker** — depends on `build`; runs only on `push` to `main`. Builds the
  Docker image, smoke-tests it (`docker run` then `curl /actuator/health`),
  and pushes to `ghcr.io/robsartin/keystone:latest` and `:sha-<sha>`.

Branch protection on `main` (via Repository Ruleset, not legacy branch
protection):

- Require PR before merging.
- Require status check `build` to pass.
- Require signed commits.
- Require linear history.
- Squash merge only.
- Dismiss stale reviews on push.
- No force pushes; no deletions.

## 10. Repo provisioning

**Prerequisite:** signed-commits enforcement on `main` requires Rob's local
git to sign commits. Before applying the ruleset, confirm
`git config --global commit.gpgsign true` and that either GPG or SSH signing
is configured. The very first commit (this spec) was created without
signing; that is acceptable because the ruleset is applied **after** the
initial push, and the rule only enforces signing on commits added after the
ruleset goes live.

Run **after Rob's explicit confirmation**, since repo creation is
user-visible:

```bash
gh repo create robsartin/keystone \
  --public \
  --license apache-2.0 \
  --description "Spring Boot general ledger keystone" \
  --source ~/code/keystone \
  --remote origin \
  --push
```

Then apply topics and the ruleset:

```bash
gh repo edit robsartin/keystone --add-topic spring-boot,java,general-ledger,\
hexagonal-architecture,archunit,prometheus,grafana,tdd,double-entry,\
ports-and-adapters
gh api repos/robsartin/keystone/rulesets --method POST \
  --input docs/ruleset-main.json
```

`docs/ruleset-main.json` is checked in alongside the repo.

## 11. Open questions / follow-ups for later slices

- **Slice 2:** Real `Account` aggregate with type and normal side; chart of
  accounts CRUD; replace `AccountCode` with `Account` references.
- **Slice 3:** `Period` and posting-date validation (cannot post into a
  closed period).
- **Slice 4:** Trial balance query.
- **Slice 5:** Tenancy + auth (OAuth2 resource server + per-tenant data
  isolation).
- **Slice 6:** Multi-currency revaluation.
- Mutation-score threshold may need to rise from 60% as the codebase
  matures.
- Decide whether to publish the Docker image to GHCR public or keep private
  even though the source is public.

## 12. Acceptance criteria for the keystone

The keystone is "done" when:

1. `git clone && ./mvnw -B verify` is green from a cold cache on macOS and
   Ubuntu CI.
2. `docker compose up -d && ./mvnw spring-boot:run` produces a working app
   that accepts a balanced `POST /journal-entries`, persists it to Postgres,
   and shows the counter increment in Grafana within ~15 seconds.
3. A deliberately unbalanced request returns 400 with a ProblemDetails body
   matching the spec in §5.2.
4. Removing one assertion from any production class fails at least one test
   (mutation coverage proxy).
5. All nine ADRs are merged into `docs/adr/` and referenced from `CLAUDE.md`
   and `README.md`.
6. The committed `docs/openapi/openapi.yaml` matches the spec generated from
   the running app.
