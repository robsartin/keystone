# Keystone Foundation — Plan 2: Walking Skeleton

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the foundation cleanups from Plan 1's whole-branch review, then stand up an end-to-end Spring Boot 4.0.3 walking skeleton: `POST /journal-entries` accepts a balanced double-entry request, persists it via JPA + Postgres + Flyway, returns RFC 9457 ProblemDetails on failures, exposes custom Prometheus metrics + structured JSON logs, and is gated by four OpenAPI quality checks.

**Architecture:** Hexagonal layering (already established). Plan 2 adds the `infrastructure` layer: `infrastructure.persistence.journal` (JPA adapter), `infrastructure.web` (controller + DTOs + ResultMapper), `infrastructure.observability` (Micrometer + Logback), `infrastructure.config` (Spring config + ID generator). The domain gains a typed `JournalEntryId` wrapper around `UUID` and a `PersistedJournalEntry(id, entry)` wrapper that the repository port returns.

**Tech Stack:** Spring Boot 4.0.3 (web-mvc, data-jpa, actuator, validation), Postgres 16 (JPA + Flyway), Testcontainers Postgres, Micrometer + `micrometer-registry-prometheus`, Logback + `logstash-logback-encoder`, springdoc-openapi 2.8.8, Spectral (via npx in CI / Maven exec locally), `openapi-diff` Maven plugin, dependency-check Maven plugin. UUID v7 hand-rolled (~15 lines, zero deps).

**Pre-condition:** On `main` at `/Users/sartin/code/keystone`, fully synced post-Plan-1 merge. Branch from `main` as `11-plan-2-walking-skeleton` (matching issue #11). All Plan 2 commits land on that branch; one PR closes #3-#11 at merge.

**Definition of done:**

1. `./mvnw -B verify` green from cold cache (Spotless, Checkstyle, Surefire, Failsafe with Testcontainers, JaCoCo ≥85% line, ArchUnit, OpenAPI snapshot diff).
2. `./mvnw -B verify -Pmutation` green (PIT ≥60% on `domain..` + `application..`, post-prep refactors).
3. `./mvnw -B verify -Popenapi-update` regenerates `docs/openapi/openapi.yaml` cleanly when an API change is intentional.
4. `docker run -d --name kp -p 5434:5432 -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone -e POSTGRES_DB=keystone postgres:16` then `./mvnw spring-boot:run` boots the app on `:8080`.
5. `POST /journal-entries` with a balanced entry returns `201 Created` + `Location: /journal-entries/<UUID-v7>`; the entry is in Postgres.
6. An unbalanced/mixed-currency/overflow request returns `400` with RFC 9457 ProblemDetails; the new `JournalError.Overflow` variant maps to its own problem type URI.
7. `curl localhost:8080/actuator/prometheus | grep keystone_journal_entries_posted_total` shows the counter, split by `result` label.
8. ADRs 0005, 0006, 0008, 0010 committed and referenced from CLAUDE.md.
9. Issues #3-#10 closed by the PR; #11 closed when merged.

---

## File Structure

**Created or modified in Plan 2:**

| Path | Responsibility | Plan 2 phase |
|---|---|---|
| `pom.xml` | Add Spring Boot starters, Flyway, Testcontainers, springdoc, openapi-diff, dependency-check, failsafe; PIT moved into a profile | A (prep) + B (bootstrap) |
| `checkstyle.xml` | Unchanged |  |
| `CLAUDE.md` | Updated with Plan 2 quick-reference (verify modes, run profiles, OpenAPI gate workflow) | F (finalize) |
| `README.md` | Updated with Plan 2 status + run instructions | F (finalize) |
| `.spectral.yaml` | OpenAPI lint rules (operationId, summary, 4xx response, $ref-only schemas, ProblemDetails on errors) | E (OpenAPI) |
| `docs/adr/README.md` | Reserved-numbers table | A (#9) |
| `docs/adr/0005-postgres-flyway.md` | Persistence decision | B |
| `docs/adr/0006-openapi-gates.md` | Four-layer OpenAPI gate | E |
| `docs/adr/0008-observability.md` | Micrometer, structured logs, MDC | D |
| `docs/adr/0010-journal-entry-id-wrapper.md` | `PersistedJournalEntry` + `JournalEntryId(UUID)` + UUID v7 | A (#4) |
| `docs/openapi/openapi.yaml` | Committed OpenAPI snapshot of the running app | E |
| `src/main/java/.../KeystoneApplication.java` | `@SpringBootApplication` entrypoint | B |
| `src/main/java/.../domain/journal/JournalEntry.java` | `sum()` returns `Result<Money, JournalError>`, propagates Overflow | A (#3) |
| `src/main/java/.../domain/journal/JournalError.java` | New `Overflow(Side)` variant | A (#3) |
| `src/main/java/.../domain/journal/JournalEntryId.java` | `record JournalEntryId(UUID value)` | A (#4) |
| `src/main/java/.../domain/journal/PersistedJournalEntry.java` | `record PersistedJournalEntry(JournalEntryId, JournalEntry)` | A (#4) |
| `src/main/java/.../domain/journal/JournalEntryRepository.java` | Port shape: `save → PersistedJournalEntry`, `findById(JournalEntryId)` | A (#4) |
| `src/main/java/.../domain/money/Money.java` | `negate()` returns `Result<Money, MoneyError>` using `Math.negateExact` | A (#7) |
| `src/main/java/.../application/journal/PostJournalEntryService.java` | Returns `Result<PersistedJournalEntry, JournalError>` | A (#4) |
| `src/main/java/.../infrastructure/shared/UuidV7Generator.java` | Hand-rolled UUID v7 utility | A (#4) |
| `src/main/java/.../infrastructure/persistence/journal/JournalEntryEntity.java` | JPA entity for the entry header row | B |
| `src/main/java/.../infrastructure/persistence/journal/PostingEntity.java` | JPA entity for individual postings | B |
| `src/main/java/.../infrastructure/persistence/journal/JpaJournalEntryRepository.java` | JPA adapter implementing the port | B |
| `src/main/java/.../infrastructure/persistence/journal/JournalEntryEntityMapper.java` | Domain ↔ entity mapping | B |
| `src/main/java/.../infrastructure/web/JournalEntryController.java` | `@RestController` POST /journal-entries | C |
| `src/main/java/.../infrastructure/web/dto/PostJournalEntryRequest.java` | Request DTO + Bean Validation | C |
| `src/main/java/.../infrastructure/web/dto/PostingRequest.java` | Posting DTO | C |
| `src/main/java/.../infrastructure/web/dto/JournalEntryResponse.java` | Response DTO | C |
| `src/main/java/.../infrastructure/web/dto/PostingResponse.java` | Response DTO | C |
| `src/main/java/.../infrastructure/web/ResultMapper.java` | `Result.Failure<JournalError>` → `ProblemDetail` | C |
| `src/main/java/.../infrastructure/web/CorrelationIdFilter.java` | MDC correlation ID per request | D |
| `src/main/java/.../infrastructure/observability/MetricsConfig.java` | `journal_entries_posted_total` counter, post-duration timer | D |
| `src/main/java/.../infrastructure/config/PersistenceConfig.java` | `JournalEntryIdGenerator` + `JpaJournalEntryRepository` bean wiring | B |
| `src/main/java/.../infrastructure/config/ApplicationConfig.java` | `PostJournalEntryService` bean | B |
| `src/main/resources/application.yaml` | Default profile config | B |
| `src/main/resources/application-local.yaml` | Local dev profile | B |
| `src/main/resources/application-test.yaml` | Test profile (Testcontainers JDBC URL pattern) | B |
| `src/main/resources/logback-spring.xml` | JSON encoder in `prod`/`local`-prod, console encoder in `local` | D |
| `src/main/resources/db/migration/V1__journal_entries.sql` | Flyway baseline | B |
| `src/test/java/.../domain/journal/JournalEntryTest.java` | New Overflow test | A (#3) |
| `src/test/java/.../domain/journal/PersistedJournalEntryTest.java` | Wrapper tests | A (#4) |
| `src/test/java/.../domain/journal/JournalEntryIdTest.java` | Wrapper tests | A (#4) |
| `src/test/java/.../domain/money/MoneyTest.java` | Updated negate tests | A (#7) |
| `src/test/java/.../domain/account/AccountCodeTest.java` | Split mixed-assertion test | A (#8) |
| `src/test/java/.../application/journal/PostJournalEntryServiceTest.java` | Updated for PersistedJournalEntry | A (#4) |
| `src/test/java/.../architecture/HexagonalArchitectureTest.java` | Comment about ImportOption.DoNotIncludeTests | A (#10) |
| `src/test/java/.../infrastructure/shared/UuidV7GeneratorTest.java` | UUID v7 generator tests | A (#4) |
| `src/test/java/.../infrastructure/persistence/journal/JpaJournalEntryRepositoryIT.java` | Testcontainers integration test | B |
| `src/test/java/.../infrastructure/web/JournalEntryControllerTest.java` | MockMvc tests for the controller | C |
| `src/test/java/.../infrastructure/web/ResultMapperTest.java` | Result → ProblemDetail mapping | C |
| `src/test/java/.../infrastructure/observability/MetricsConfigTest.java` | Counter/timer registered | D |
| `src/test/java/.../api/OpenApiSnapshotTest.java` | Diffs target/openapi.yaml against committed snapshot | E |
| `src/test/java/.../smoke/ApplicationSmokeIT.java` | Full `@SpringBootTest`, posts an entry, asserts metric increment | F |

**Not changed in Plan 2:** `Result`, `MoneyError`, `AccountCode`, `Side`, `Posting`, `Side` enum (besides Side reference in JournalError.Overflow), the existing 5 ADRs.

---

## Phase Order

Phases must run in order:

- **Phase A — Prep cleanups** (Tasks 1-8) — closes issues #3, #4, #5, #6, #7, #8, #9, #10. Establishes the type changes (`PersistedJournalEntry`, `JournalError.Overflow`, `Money.negate` Result) that Phases B-F depend on.
- **Phase B — Spring Boot bootstrap + persistence** (Tasks 9-15) — `KeystoneApplication`, profiles, JPA entities, Flyway V1, JPA adapter, Testcontainers IT.
- **Phase C — Web layer** (Tasks 16-20) — DTOs, controller, ResultMapper, MockMvc tests.
- **Phase D — Observability** (Tasks 21-23) — Logback JSON, MDC filter, Micrometer.
- **Phase E — OpenAPI gates** (Tasks 24-27) — springdoc plugin, Spectral lint, snapshot diff, openapi-diff.
- **Phase F — Smoke + finalize** (Tasks 28-29) — `ApplicationSmokeIT`, README/CLAUDE.md updates, final cold-cache verify.

Phase A tasks are independent of each other except where noted; Phases B-F are largely sequential within themselves.

---

# Phase A — Prep cleanups

Each task closes one issue. Each commit message ends with `Closes #N`.

---

## Task 1: Fix `Money.negate()` on `Long.MIN_VALUE` (closes #7)

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/money/Money.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/money/MoneyTest.java`

- [ ] **Step 1: Update the failing test (RED)**

In `MoneyTest.java`, replace the existing `shouldFlipSignWhenNegateCalled` test with the two tests below, and add the new overflow test:

```java
    @Test
    @DisplayName("negate() returns Success(-amount) for non-MIN values")
    void shouldFlipSignWhenNegateCalledOnNonMinValue() {
        Result<Money, MoneyError> r = new Money(100L, USD).negate();
        assertInstanceOf(Result.Success.class, r);
        assertEquals(new Money(-100L, USD), ((Result.Success<Money, MoneyError>) r).value());
    }

    @Test
    @DisplayName("negate() returns Success(0) when amount is zero")
    void shouldReturnZeroWhenNegateCalledOnZero() {
        Result<Money, MoneyError> r = new Money(0L, USD).negate();
        assertInstanceOf(Result.Success.class, r);
        assertEquals(new Money(0L, USD), ((Result.Success<Money, MoneyError>) r).value());
    }

    @Test
    @DisplayName("negate() returns Failure(Overflow) on Long.MIN_VALUE")
    void shouldReturnOverflowWhenNegateOnLongMinValue() {
        Result<Money, MoneyError> r = new Money(Long.MIN_VALUE, USD).negate();
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(MoneyError.Overflow.class,
                ((Result.Failure<Money, MoneyError>) r).error());
    }
```

- [ ] **Step 2: Verify the test fails to compile**

```bash
./mvnw -B test -Dtest=MoneyTest 2>&1 | tail -10
```

Expected: `cannot find symbol method negate()` returning `Result` (or similar — the existing `negate()` returns `Money`, not `Result<Money, MoneyError>`).

- [ ] **Step 3: Update `Money.negate()` to return Result**

Replace the existing `negate()` method in `Money.java` with:

```java
    public Result<Money, MoneyError> negate() {
        try {
            return Result.success(new Money(Math.negateExact(minorUnits), currency));
        } catch (ArithmeticException ignored) {
            return Result.failure(new MoneyError.Overflow());
        }
    }
```

- [ ] **Step 4: Verify all MoneyTest cases pass**

```bash
./mvnw -B test -Dtest=MoneyTest 2>&1 | tail -10
```

Expected: `Tests run: 13, Failures: 0` (was 11; added 2 new, replaced 1 with 2 — net +2). Wait — the count is: original 11 minus 1 (replaced `shouldFlipSign...`) = 10, plus 3 new = 13. Confirm 13.

- [ ] **Step 5: Spotless apply + full verify**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. Total tests bumps to 63 (was 61, plus 2 net new in MoneyTest).

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
fix(domain): Money.negate() returns Result and rejects Long.MIN_VALUE overflow

Was the only piece of arithmetic in the codebase that didn't follow
"fail loud or return Result". Math.negateExact catches the
two's-complement edge case where -Long.MIN_VALUE silently wraps to
Long.MIN_VALUE.

Closes #7

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Propagate sum overflow as `JournalError.Overflow` (closes #3)

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`

- [ ] **Step 1: Add the failing test (RED)**

Append this test to `JournalEntryTest.java`, just before the closing `}`:

```java
    @Test
    @DisplayName("of() returns Failure(Overflow) when same-side postings sum past Long.MAX_VALUE")
    void shouldReturnOverflowWhenSameSidePostingsExceedLongRange() {
        long half = Long.MAX_VALUE / 2 + 1;
        Posting bigDebit1 = new Posting(CASH, Side.DEBIT, new Money(half, USD));
        AccountCode receivable = new AccountCode("1100");
        Posting bigDebit2 = new Posting(receivable, Side.DEBIT, new Money(half, USD));
        Posting smallCredit = new Posting(EQUITY, Side.CREDIT, new Money(1L, USD));

        Result<JournalEntry, JournalError> r =
                JournalEntry.of(TODAY, "overflow", List.of(bigDebit1, bigDebit2, smallCredit));

        assertInstanceOf(Result.Failure.class, r);
        JournalError e = ((Result.Failure<JournalEntry, JournalError>) r).error();
        assertInstanceOf(JournalError.Overflow.class, e);
        assertEquals(Side.DEBIT, ((JournalError.Overflow) e).side());
    }
```

- [ ] **Step 2: Run, confirm fail**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -15
```

Expected: `cannot find symbol class JournalError.Overflow` (or, if you choose to add the variant first, an `ArithmeticException` thrown by the existing `JournalEntry.sum()`).

- [ ] **Step 3: Add `Overflow(Side side)` variant to `JournalError`**

Replace the contents of `JournalError.java` with:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import java.util.Set;

/** Reasons a {@link JournalEntry} factory may refuse to construct an entry. */
public sealed interface JournalError {

    /** No postings supplied. */
    record NoPostings() implements JournalError {}

    /** Postings reference more than one currency. */
    record MixedCurrencies(Set<Currency> currencies) implements JournalError {}

    /** Sum of debits does not equal sum of credits. */
    record Unbalanced(Money debits, Money credits) implements JournalError {}

    /** Same-side posting sum overflowed Long.MAX_VALUE. */
    record Overflow(Side side) implements JournalError {}
}
```

- [ ] **Step 4: Update `JournalEntry.sum()` to return `Result` and `JournalEntry.of()` to compose**

Replace the `of()` and `sum()` methods in `JournalEntry.java` with:

```java
    public static Result<JournalEntry, JournalError> of(
            LocalDate occurredOn, String description, List<Posting> postings) {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(postings, "postings");

        if (postings.isEmpty()) {
            return Result.failure(new JournalError.NoPostings());
        }

        Set<Currency> currencies = postings.stream()
                .map(p -> p.amount().currency())
                .collect(Collectors.toUnmodifiableSet());
        if (currencies.size() > 1) {
            return Result.failure(new JournalError.MixedCurrencies(currencies));
        }
        Currency currency = currencies.iterator().next();

        Money zero = new Money(0L, currency);
        return sum(postings, Side.DEBIT, zero)
                .flatMap(debits -> sum(postings, Side.CREDIT, zero)
                        .flatMap(credits -> {
                            if (debits.minorUnits() != credits.minorUnits()) {
                                return Result.failure(new JournalError.Unbalanced(debits, credits));
                            }
                            return Result.success(
                                    new JournalEntry(occurredOn, description, currency, postings));
                        }));
    }

    private static Result<Money, JournalError> sum(
            List<Posting> postings, Side side, Money zero) {
        Money acc = zero;
        for (Posting p : postings) {
            if (p.side() == side) {
                Result<Money, MoneyError> next = acc.plus(p.amount());
                if (next instanceof Result.Success<Money, MoneyError> s) {
                    acc = s.value();
                } else {
                    return Result.failure(new JournalError.Overflow(side));
                }
            }
        }
        return Result.success(acc);
    }
```

- [ ] **Step 5: Verify test passes**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0` (was 9; added 1).

- [ ] **Step 6: Spotless apply + full verify**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. JaCoCo line coverage on the bundle should now hit 100% (the previously uncovered defensive throw is gone). Total tests = 64.

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
fix(domain): propagate sum overflow as JournalError.Overflow

JournalEntry.sum() previously threw ArithmeticException when same-side
postings overflowed long, violating ADR-0004's "expected failures
return Result" rule. Add JournalError.Overflow(Side) variant; sum()
returns Result; of() composes via flatMap. Bundle line coverage now
100%.

Closes #3

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Introduce `JournalEntryId`, `PersistedJournalEntry`, UUID v7 generator, ADR-0010 (closes #4)

This is the largest prep task. The port shape change ripples through `PostJournalEntryService`. Includes the new infrastructure utility and its tests.

**Files:**
- Create: `docs/adr/0010-journal-entry-id-wrapper.md`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryId.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntry.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/shared/UuidV7Generator.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryIdTest.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntryTest.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/shared/UuidV7GeneratorTest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`

- [ ] **Step 1: Write ADR-0010**

Create `docs/adr/0010-journal-entry-id-wrapper.md`:

```markdown
# ADR-0010: JournalEntryId wrapper with UUID v7; PersistedJournalEntry separates intent from persisted state

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

Plan 1's `JournalEntryRepository.save()` returned `JournalEntry` unchanged
with a "Plan 2 introduces an identifier wrapper" placeholder. Plan 2 wires
real persistence (JPA + Postgres + Flyway), so we need to decide:

1. Where the entry's identity lives.
2. The format/algorithm for generating IDs.
3. How to distinguish a constructed-but-not-persisted `JournalEntry` from
   one that already exists in storage.

## Decision

**Wrapper type.** Introduce
`record PersistedJournalEntry(JournalEntryId id, JournalEntry entry)`.
The repository's `save()` returns the wrapper; `findById()` returns
`Optional<PersistedJournalEntry>`. `JournalEntry` itself stays a pure
value object representing the *intent* of an entry;
`PersistedJournalEntry` represents the *fact* that one exists in
storage.

**ID type.** `record JournalEntryId(UUID value)` — typed wrapper around
`java.util.UUID`. Forbids accidentally passing a raw UUID where a
JournalEntryId is expected (and vice versa for other id types added in
future slices).

**ID format.** UUID v7 per RFC 9562 — 128-bit time-ordered identifiers.
Time prefix (48 bits unix-millis) gives natural sort order in the
database, useful for both index locality (B-trees) and for
"show me the last N entries" queries. Generated by a hand-rolled
`infrastructure.shared.UuidV7Generator` (~15 lines) — no external
library needed. JDK 25's `UUID` class doesn't yet have a v7 factory;
when one lands we will switch.

**Generation site.** The infrastructure adapter (JPA repository)
generates the ID at save time. The domain has no dependency on UUID
generation; tests can mint deterministic IDs.

## Consequences

- Domain `JournalEntry` stays pristine and immutable. No nullable id
  field, no "is this saved?" runtime check.
- Type system distinguishes "this is what I want to save"
  (`JournalEntry`) from "this exists" (`PersistedJournalEntry`).
- `findById()` takes a typed `JournalEntryId`, not a raw `UUID` or
  `String` — fewer mistakes at the controller boundary.
- UUID v7 is a Postgres-native type (`uuid` column), so persistence is
  direct.
- We accept that ID generation lives outside the domain. If we later
  want client-supplied IDs, the port shape can absorb that with no
  domain changes.
- `JournalEntry`'s record-generated `equals`/`hashCode` compare values,
  not identity. Two entries with the same fields but different stored
  IDs would compare equal as `JournalEntry` instances. This is correct
  — identity lives in the `PersistedJournalEntry` wrapper.
```

- [ ] **Step 2: Write `JournalEntryIdTest.java` (RED)**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryIdTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntryId")
class JournalEntryIdTest {

    @Test
    @DisplayName("rejects null UUID")
    void shouldThrowWhenValueIsNull() {
        assertThrows(NullPointerException.class, () -> new JournalEntryId(null));
    }

    @Test
    @DisplayName("equality is value-based on UUID")
    void shouldBeEqualWhenSameUuid() {
        UUID u = UUID.randomUUID();
        assertEquals(new JournalEntryId(u), new JournalEntryId(u));
    }

    @Test
    @DisplayName("different UUIDs produce different ids")
    void shouldDifferWhenDifferentUuids() {
        assertNotEquals(new JournalEntryId(UUID.randomUUID()),
                new JournalEntryId(UUID.randomUUID()));
    }
}
```

- [ ] **Step 3: Verify compile fails**

```bash
./mvnw -B test -Dtest=JournalEntryIdTest 2>&1 | tail -10
```

Expected: `cannot find symbol class JournalEntryId`.

- [ ] **Step 4: Write `JournalEntryId.java`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryId.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identifier for a {@link JournalEntry}.
 *
 * <p>Wraps a {@link UUID} (UUID v7 in production via the infrastructure
 * generator) so it cannot be confused with other id types added in future
 * slices.
 */
public record JournalEntryId(UUID value) {

    public JournalEntryId {
        Objects.requireNonNull(value, "value");
    }
}
```

- [ ] **Step 5: Verify pass**

```bash
./mvnw -B test -Dtest=JournalEntryIdTest 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 6: Write `PersistedJournalEntryTest.java` (RED)**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntryTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PersistedJournalEntry")
class PersistedJournalEntryTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static JournalEntry validEntry() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(
                LocalDate.parse("2026-05-10"),
                "x",
                List.of(
                        new Posting(new AccountCode("1000"), Side.DEBIT, new Money(1L, USD)),
                        new Posting(new AccountCode("3000"), Side.CREDIT, new Money(1L, USD))));
        return ((Result.Success<JournalEntry, JournalError>) r).value();
    }

    @Test
    @DisplayName("rejects null id")
    void shouldThrowWhenIdIsNull() {
        assertThrows(NullPointerException.class,
                () -> new PersistedJournalEntry(null, validEntry()));
    }

    @Test
    @DisplayName("rejects null entry")
    void shouldThrowWhenEntryIsNull() {
        assertThrows(NullPointerException.class,
                () -> new PersistedJournalEntry(new JournalEntryId(UUID.randomUUID()), null));
    }

    @Test
    @DisplayName("exposes id and entry as record components")
    void shouldExposeIdAndEntryWhenConstructed() {
        JournalEntry entry = validEntry();
        JournalEntryId id = new JournalEntryId(UUID.randomUUID());
        PersistedJournalEntry p = new PersistedJournalEntry(id, entry);
        assertSame(id, p.id());
        assertSame(entry, p.entry());
    }

    @Test
    @DisplayName("equality is value-based")
    void shouldBeEqualWhenIdAndEntryMatch() {
        JournalEntry entry = validEntry();
        JournalEntryId id = new JournalEntryId(UUID.randomUUID());
        assertEquals(new PersistedJournalEntry(id, entry), new PersistedJournalEntry(id, entry));
    }
}
```

- [ ] **Step 7: Verify compile fails**

```bash
./mvnw -B test -Dtest=PersistedJournalEntryTest 2>&1 | tail -10
```

Expected: `cannot find symbol class PersistedJournalEntry`.

- [ ] **Step 8: Write `PersistedJournalEntry.java`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntry.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;

/**
 * A {@link JournalEntry} that has been persisted, paired with its
 * storage-assigned {@link JournalEntryId}.
 */
public record PersistedJournalEntry(JournalEntryId id, JournalEntry entry) {

    public PersistedJournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
    }
}
```

- [ ] **Step 9: Verify pass**

```bash
./mvnw -B test -Dtest=PersistedJournalEntryTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 10: Write `UuidV7GeneratorTest.java` (RED)**

Create `src/test/java/co/embracejoy/accounting/keystone/infrastructure/shared/UuidV7GeneratorTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UuidV7Generator")
class UuidV7GeneratorTest {

    @Test
    @DisplayName("create() returns a UUID of version 7")
    void shouldReturnVersion7Uuid() {
        assertEquals(7, UuidV7Generator.create().version());
    }

    @Test
    @DisplayName("create() returns a UUID of RFC 4122 variant")
    void shouldReturnRfc4122Variant() {
        assertEquals(2, UuidV7Generator.create().variant());
    }

    @Test
    @DisplayName("repeated calls produce distinct UUIDs")
    void shouldProduceDistinctUuids() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(UuidV7Generator.create()), "duplicate UUID generated");
        }
    }

    @Test
    @DisplayName("UUIDs created later have a non-decreasing timestamp prefix")
    void shouldHaveNonDecreasingTimestampWhenCreatedSequentially() throws InterruptedException {
        UUID a = UuidV7Generator.create();
        Thread.sleep(2);
        UUID b = UuidV7Generator.create();
        long tsA = a.getMostSignificantBits() >>> 16;
        long tsB = b.getMostSignificantBits() >>> 16;
        assertTrue(tsB >= tsA, "expected timestamp B (" + tsB + ") >= A (" + tsA + ")");
    }
}
```

- [ ] **Step 11: Verify compile fails**

```bash
./mvnw -B test -Dtest=UuidV7GeneratorTest 2>&1 | tail -10
```

Expected: `cannot find symbol class UuidV7Generator`.

- [ ] **Step 12: Write `UuidV7Generator.java`**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/shared/UuidV7Generator.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates UUID v7 identifiers per RFC 9562: 128-bit time-ordered, sortable.
 *
 * <p>Layout (most-significant bit first):
 *
 * <pre>
 *   | 48 bits unix-millis timestamp | 4 bits version (0x7) | 12 bits random_a |
 *   | 2 bits variant (0b10) | 62 bits random_b |
 * </pre>
 *
 * <p>JDK 25's {@link UUID} class does not provide a v7 factory; when one
 * lands upstream this class becomes a thin wrapper.
 */
public final class UuidV7Generator {

    private static final SecureRandom RNG = new SecureRandom();

    private UuidV7Generator() {}

    public static UUID create() {
        long ts = Instant.now().toEpochMilli();
        long randA = RNG.nextInt(0x1000); // 12 bits
        long randB = RNG.nextLong() & 0x3FFFFFFFFFFFFFFFL; // 62 bits

        long msb = (ts << 16) | (0x7L << 12) | randA;
        long lsb = (1L << 63) | randB;

        return new UUID(msb, lsb);
    }
}
```

- [ ] **Step 13: Verify pass**

```bash
./mvnw -B test -Dtest=UuidV7GeneratorTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 14: Update `JournalEntryRepository` port**

Replace the contents of `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java` with:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

    /** Persist the given entry; the adapter assigns a {@link JournalEntryId}. */
    PersistedJournalEntry save(JournalEntry entry);

    /** Find a persisted entry by id, or {@code Optional.empty()}. */
    Optional<PersistedJournalEntry> findById(JournalEntryId id);
}
```

- [ ] **Step 15: Update `PostJournalEntryService`**

Replace the contents of
`src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
with:

```java
package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Use case: post a balanced journal entry, persisting it through the repository port. */
public final class PostJournalEntryService {

    private final JournalEntryRepository repository;

    public PostJournalEntryService(JournalEntryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<PersistedJournalEntry, JournalError> post(
            LocalDate occurredOn, String description, List<Posting> postings) {
        return JournalEntry.of(occurredOn, description, postings).map(repository::save);
    }
}
```

- [ ] **Step 16: Update `PostJournalEntryServiceTest`**

Replace the FakeRepo and the success-path assertion in
`src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`.
The full updated file:

```java
package co.embracejoy.accounting.keystone.application.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostJournalEntryService")
class PostJournalEntryServiceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountCode CASH = new AccountCode("1000");
    private static final AccountCode EQUITY = new AccountCode("3000");
    private static final LocalDate TODAY = LocalDate.parse("2026-05-10");

    private static final class FakeRepo implements JournalEntryRepository {
        final List<PersistedJournalEntry> saved = new ArrayList<>();

        @Override
        public PersistedJournalEntry save(JournalEntry entry) {
            PersistedJournalEntry p = new PersistedJournalEntry(
                    new JournalEntryId(UUID.randomUUID()), entry);
            saved.add(p);
            return p;
        }

        @Override
        public Optional<PersistedJournalEntry> findById(JournalEntryId id) {
            return Optional.empty();
        }
    }

    private static Posting debit(AccountCode a, long amt) {
        return new Posting(a, Side.DEBIT, new Money(amt, USD));
    }

    private static Posting credit(AccountCode a, long amt) {
        return new Posting(a, Side.CREDIT, new Money(amt, USD));
    }

    @Test
    @DisplayName("persists and returns Success when request is valid")
    void shouldPersistAndReturnSuccessWhenRequestIsValid() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<PersistedJournalEntry, JournalError> r = service.post(
                TODAY, "opening", List.of(debit(CASH, 1000L), credit(EQUITY, 1000L)));

        assertInstanceOf(Result.Success.class, r);
        assertEquals(1, repo.saved.size());
        PersistedJournalEntry persisted =
                ((Result.Success<PersistedJournalEntry, JournalError>) r).value();
        assertSame(persisted, repo.saved.get(0));
    }

    @Test
    @DisplayName("returns Failure and does not persist when entry is unbalanced")
    void shouldReturnFailureAndNotPersistWhenUnbalanced() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<PersistedJournalEntry, JournalError> r = service.post(
                TODAY, "bad", List.of(debit(CASH, 1000L), credit(EQUITY, 999L)));

        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.Unbalanced.class,
                ((Result.Failure<PersistedJournalEntry, JournalError>) r).error());
        assertEquals(0, repo.saved.size());
    }

    @Test
    @DisplayName("returns Failure when postings are empty")
    void shouldReturnFailureWhenPostingsEmpty() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<PersistedJournalEntry, JournalError> r = service.post(TODAY, "empty", List.of());

        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.NoPostings.class,
                ((Result.Failure<PersistedJournalEntry, JournalError>) r).error());
        assertEquals(0, repo.saved.size());
    }
}
```

- [ ] **Step 17: Spotless apply + full verify**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. Total tests = 64 prior + 3 (JournalEntryId) + 4 (PersistedJournalEntry) + 4 (UuidV7Generator) = 75.

ArchUnit may fail one rule: `domainDoesNotImportInfrastructure` is OK because the domain doesn't import the new generator (the adapter does in Phase B). Confirm it stays green.

If JaCoCo dips below 85% because the new infrastructure code (UuidV7Generator) lacks coverage proportionally, the four UuidV7GeneratorTest cases should be enough. If not, add the missing branch coverage.

- [ ] **Step 18: Commit**

```bash
git add docs/adr/0010-journal-entry-id-wrapper.md src/
git commit -m "$(cat <<'EOF'
feat(domain,infrastructure): JournalEntryId + PersistedJournalEntry wrapper, UUID v7

Introduce typed JournalEntryId(UUID) wrapper and
PersistedJournalEntry(id, entry) wrapper that the repository port
returns from save() and findById(). Domain JournalEntry stays a pure
value object representing intent; PersistedJournalEntry represents
existence in storage.

UUID v7 (RFC 9562) is hand-rolled in
infrastructure.shared.UuidV7Generator (~15 lines, no external lib).
Time-ordered + 128-bit gives sortable Postgres-native ids with no
extra deps. JDK 25 lacks a built-in v7 factory; when one lands
upstream this class becomes a thin wrapper.

Captured in ADR-0010.

Closes #4

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Move PIT behind a `-Pmutation` profile (closes #5)

**Files:**
- Modify: `pom.xml`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Wrap the PIT plugin in a Maven profile**

In `pom.xml`, find the `<plugin>` block for `org.pitest:pitest-maven` (currently in `<build><plugins>`). Cut it out of `<build><plugins>` and paste it into a new `<profiles>` block at the bottom of the project, after `</build>`:

```xml
    <profiles>
        <profile>
            <id>mutation</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-maven</artifactId>
                        <version>${pitest.version}</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.pitest</groupId>
                                <artifactId>pitest-junit5-plugin</artifactId>
                                <version>${pitest.junit5.version}</version>
                            </dependency>
                        </dependencies>
                        <configuration>
                            <targetClasses>
                                <param>co.embracejoy.accounting.keystone.domain.*</param>
                                <param>co.embracejoy.accounting.keystone.application.*</param>
                            </targetClasses>
                            <targetTests>
                                <param>co.embracejoy.accounting.keystone.*</param>
                            </targetTests>
                            <mutationThreshold>60</mutationThreshold>
                            <failWhenNoMutations>true</failWhenNoMutations>
                            <outputFormats>
                                <param>HTML</param>
                                <param>XML</param>
                            </outputFormats>
                        </configuration>
                        <executions>
                            <execution>
                                <id>pitest-mutation-coverage</id>
                                <phase>verify</phase>
                                <goals><goal>mutationCoverage</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

- [ ] **Step 2: Update `CLAUDE.md` Quick Reference**

In `CLAUDE.md`, replace the "Quick reference" section with:

```markdown
## Quick reference

- `./mvnw -B verify` — fast local gate (Spotless, Checkstyle, tests, coverage, ArchUnit). PIT skipped.
- `./mvnw -B verify -Pmutation` — full gate with PIT mutation coverage (~30s+; run before pushing).
- `./mvnw spotless:apply` — auto-format Java
- `./mvnw test` — unit tests only
```

- [ ] **Step 3: Verify both modes**

```bash
./mvnw -B -q verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS, no PIT output.

```bash
./mvnw -B -q verify -Pmutation 2>&1 | tail -10
```

Expected: BUILD SUCCESS with PIT report.

- [ ] **Step 4: Commit**

```bash
git add pom.xml CLAUDE.md
git commit -m "$(cat <<'EOF'
build: move PIT mutation coverage behind a -Pmutation profile

Default ./mvnw verify stays fast (no PIT). CI activates -Pmutation;
local "before push" workflow does too. Documented in CLAUDE.md
quick-reference.

Closes #5

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Configure `maven-failsafe-plugin` explicitly (closes #6)

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/co/embracejoy/accounting/keystone/smoke/PlaceholderIT.java`

- [ ] **Step 1: Add Failsafe plugin block to `pom.xml`**

Append to `<build><plugins>` (just before `</plugins>`, after the existing surefire plugin):

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
                <executions>
                    <execution>
                        <id>integration-tests</id>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 2: Add a placeholder IT to prove Failsafe runs**

Create `src/test/java/co/embracejoy/accounting/keystone/smoke/PlaceholderIT.java`:

```java
package co.embracejoy.accounting.keystone.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Failsafe wiring smoke")
class PlaceholderIT {

    @Test
    @DisplayName("placeholder IT runs via Failsafe")
    void shouldRunViaFailsafe() {
        assertTrue(true);
    }
}
```

(This file will be DELETED in Task 28 when `ApplicationSmokeIT` lands.)

- [ ] **Step 3: Verify**

```bash
./mvnw -B -q verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS. The output should mention `[INFO] --- maven-failsafe-plugin:...:integration-test` running 1 test.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/
git commit -m "$(cat <<'EOF'
build: configure maven-failsafe-plugin with useModulePath=false

Mirrors Surefire's setting; needed for *IT.java tests to run cleanly
on JDK 25 with Spring Boot's classpath layout. Placeholder
PlaceholderIT proves the plugin runs; will be removed when the real
ApplicationSmokeIT lands in Task 28.

Closes #6

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Split `AccountCode` mixed-assertion test (closes #8)

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountCodeTest.java`

- [ ] **Step 1: Replace the mixed test with two clean tests**

In `AccountCodeTest.java`, replace `shouldDifferByCaseWhenComparedAfterTrim` with:

```java
    @Test
    @DisplayName("equality is case-sensitive: same case after trim is equal")
    void shouldBeEqualWhenSameCaseAfterTrim() {
        assertEquals(new AccountCode("AR-CASH"), new AccountCode("AR-CASH"));
    }

    @Test
    @DisplayName("equality is case-sensitive: different case is not equal")
    void shouldDifferByCaseWhenComparedAfterTrim() {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                new AccountCode("AR-CASH"), new AccountCode("ar-cash"));
    }
```

- [ ] **Step 2: Verify**

```bash
./mvnw -B test -Dtest=AccountCodeTest 2>&1 | tail -5
```

Expected: `Tests run: 6, Failures: 0` (was 5).

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
test(domain): split AccountCode test that mixed equal/not-equal assertions

A single failure now tells you which property broke.

Closes #8

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Document reserved ADR numbers (closes #9)

**Files:**
- Create: `docs/adr/README.md`

- [ ] **Step 1: Write `docs/adr/README.md`**

```markdown
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
| 0010 | (reserved — JournalEntryId wrapper + UUID v7, lands in Plan 2 Phase A) | Reserved |

## Numbering

- Sequential. The next free number is the smallest integer N such that no
  ADR-N exists and N is not in the "Reserved" rows above.
- Reservations exist when an ADR is planned for an upcoming PR/plan but
  not yet written. Update this table the same commit you write the
  reserved ADR.
- See [`0000-template.md`](0000-template.md) for the starting structure.
```

(This index will be updated by ADR-0010 in Task 3, ADR-0005 in Task 11, ADR-0008 in Task 22, ADR-0006 in Task 26 of this plan, and ADR-0009 in Plan 3.)

- [ ] **Step 2: Commit**

```bash
git add docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): add ADR README with index and reserved-number table

Prevents future contributors from grabbing a reserved number for
unrelated work.

Closes #9

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Add `ImportOption.DoNotIncludeTests` comment to `HexagonalArchitectureTest` (closes #10)

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java`

- [ ] **Step 1: Add the comment**

Modify the class-level `@AnalyzeClasses` annotation to be preceded by a Javadoc-style comment:

```java
/**
 * ArchUnit rules enforcing keystone's hexagonal layering.
 *
 * <p>{@link ImportOption.DoNotIncludeTests} is load-bearing: the
 * {@code NO_PUBLIC_METHOD_RETURNS_THROWABLE} rule below would otherwise
 * trip on JUnit's {@code assertThrows} (which legitimately returns
 * {@code Throwable}) and other test utilities. Do not remove the import
 * option without first re-scoping that rule.
 */
@AnalyzeClasses(
        packages = "co.embracejoy.accounting.keystone",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class HexagonalArchitectureTest {
```

- [ ] **Step 2: Verify**

```bash
./mvnw -B -q verify 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
docs(test): explain why HexagonalArchitectureTest excludes test sources

NO_PUBLIC_METHOD_RETURNS_THROWABLE depends on tests being excluded
from the import set; assertThrows would otherwise trip the rule.

Closes #10

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase A acceptance

After Tasks 1-8:

- 8 commits on the branch closing issues #3, #4, #5, #6, #7, #8, #9, #10
- `./mvnw -B -q verify` green
- `./mvnw -B -q verify -Pmutation` green (PIT 100% on domain+application; threshold still 60%)
- Test count: ~75 (Plan 1's 61 + 2 new in MoneyTest + 1 new in JournalEntryTest + 3 in JournalEntryIdTest + 4 in PersistedJournalEntryTest + 4 in UuidV7GeneratorTest + 1 in PlaceholderIT + 1 from AccountCode test split)
- JaCoCo line coverage 100% on the bundle
- `JournalEntryRepository` port now returns `PersistedJournalEntry` from `save()`; `findById(JournalEntryId)`

---

# Phase B — Spring Boot bootstrap + persistence

Brings up Spring Boot, wires JPA + Postgres + Flyway, ships ADR-0005, and lands the first integration test (`JpaJournalEntryRepositoryIT`) using Testcontainers Postgres.

---

## Task 9: Add Spring Boot starters and `KeystoneApplication`

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/co/embracejoy/accounting/keystone/KeystoneApplication.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`

- [ ] **Step 1: Add starters and runtime deps to `pom.xml`**

In the `<dependencies>` block, ADD (preserving existing test deps):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

Also in `<build><plugins>`, ADD just after `maven-compiler-plugin`:

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals><goal>repackage</goal></goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 2: Create `KeystoneApplication.java`**

Create `src/main/java/co/embracejoy/accounting/keystone/KeystoneApplication.java`:

```java
package co.embracejoy.accounting.keystone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the keystone general ledger. */
@SpringBootApplication
public class KeystoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeystoneApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `ApplicationConfig.java` (wires the domain-pure service as a bean)**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires application-layer (domain-pure) services as Spring beans. */
@Configuration
public class ApplicationConfig {

    @Bean
    public PostJournalEntryService postJournalEntryService(JournalEntryRepository repository) {
        return new PostJournalEntryService(repository);
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS. Spring Boot dependencies resolve.

- [ ] **Step 5: Update `HexagonalArchitectureTest`**

The new `infrastructure.config.ApplicationConfig` imports `org.springframework`. Verify the existing rules still pass — they should, because the rule is `applicationDoesNotImportSpring`, not `infrastructureDoesNotImportSpring`. Run:

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/
git commit -m "$(cat <<'EOF'
feat: bootstrap Spring Boot 4.0.3 (web-mvc, data-jpa, actuator, validation, flyway)

Adds the runtime starters, KeystoneApplication entrypoint, and an
ApplicationConfig that wires the domain-pure PostJournalEntryService
as a Spring bean. Postgres JDBC driver in runtime scope; Testcontainers
+ spring-boot-testcontainers in test scope for upcoming integration
tests. spring-boot-maven-plugin's repackage goal produces the runnable
fat jar.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Add `application.yaml` profiles (default + local + test)

**Files:**
- Create: `src/main/resources/application.yaml`
- Create: `src/main/resources/application-local.yaml`
- Create: `src/main/resources/application-test.yaml`
- Create: `src/main/resources/banner.txt` (optional but nice)

- [ ] **Step 1: Default config**

Create `src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: keystone
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5434/keystone}
    username: ${DATABASE_USER:keystone}
    password: ${DATABASE_PASSWORD:keystone}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        format_sql: false
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

server:
  port: 8080
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}

logging:
  level:
    root: INFO
    co.embracejoy.accounting.keystone: INFO
    org.hibernate.SQL: WARN
```

- [ ] **Step 2: Local profile**

Create `src/main/resources/application-local.yaml`:

```yaml
logging:
  level:
    root: INFO
    co.embracejoy.accounting.keystone: DEBUG
    org.hibernate.SQL: INFO
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
```

- [ ] **Step 3: Test profile (Testcontainers JDBC URL pattern)**

Create `src/main/resources/application-test.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:16:///keystone_test
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver
    username: test
    password: test
  flyway:
    enabled: true
```

Note: `jdbc:tc:postgresql:16:///` is Testcontainers' "JDBC URL scheme" — the driver auto-spins a Postgres 16 container per JVM. This is the simplest config; later tests use `@ServiceConnection` for finer control.

- [ ] **Step 4: Banner (optional)**

Create `src/main/resources/banner.txt`:

```
   _  __                  _
  | |/ /___ _   _ ___ ___| |_ ___  _ __   ___
  | ' // _ \ | | / __/ __| __/ _ \| '_ \ / _ \
  | . \  __/ |_| \__ \__ \ || (_) | | | |  __/
  |_|\_\___|\__, |___/___/\__\___/|_| |_|\___|
            |___/   v${application.version}
```

- [ ] **Step 5: Verify**

```bash
./mvnw -B -q verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. (No application context starts yet because no `@SpringBootTest` exists.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/
git commit -m "$(cat <<'EOF'
feat(config): application.yaml with default, local, and test profiles

Default profile reads DATABASE_URL/USER/PASSWORD env vars (defaults
target Plan 3's docker-compose Postgres on port 5434). Local profile
adds DEBUG-level domain logs and SQL formatting. Test profile uses
Testcontainers' jdbc:tc URL scheme so any test that picks the
"test" profile gets an auto-spun Postgres 16 container.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: ADR-0005 (Postgres + Flyway) and Flyway V1 migration

**Files:**
- Create: `docs/adr/0005-postgres-flyway.md`
- Modify: `docs/adr/README.md` (move 0005 from Reserved to Accepted)
- Create: `src/main/resources/db/migration/V1__journal_entries.sql`

- [ ] **Step 1: Write ADR-0005**

Create `docs/adr/0005-postgres-flyway.md`:

```markdown
# ADR-0005: Postgres + Flyway from day one

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

A general ledger lives or dies on its persistence and transaction story.
Embedded H2 lies about Postgres-specific behavior (UUID type, JSONB,
window functions, partial indexes) and would force a painful migration
later. Per the foundation design spec §3, the keystone uses real
Postgres from day one.

We need:

- Versioned migrations runnable from CI and in production
- Local dev convenience (one container, no manual schema management)
- Integration tests that exercise the same DDL as production

## Decision

- **Postgres 16** as the only supported database.
- **Flyway** for schema migrations, with versioned files in
  `src/main/resources/db/migration/V<N>__<slug>.sql`. `baseline-on-migrate=false`
  so a malformed initial state fails loud.
- **`hibernate.ddl-auto=validate`** — Hibernate validates that the JPA
  entities match the live schema; it never creates or alters tables.
  All DDL goes through Flyway.
- **Testcontainers** in tests (`org.testcontainers:postgresql` +
  `spring-boot-testcontainers`). Tests targeting the persistence
  adapter run against a real Postgres 16 container, not a mock or
  embedded DB.
- **UUID** column type for the journal entry primary key; matches
  ADR-0010's UUID v7 ids natively.

## Consequences

- Local dev requires a running Postgres (Plan 3's `docker-compose` makes
  this one command; before that, manual `docker run`).
- CI requires Docker available for Testcontainers.
- Schema changes go through PR review (the migration file diff is
  visible).
- Hibernate-only schema generation is forbidden — anyone adding a JPA
  entity must add a corresponding Flyway migration.
- Multi-database support is explicitly NOT a goal. If we ever need to
  support another database, it's a new ADR.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

In the index table, change the row for 0005 from:

```
| 0005 | (reserved — Postgres + Flyway, lands in Plan 2 Phase B) | Reserved |
```

to:

```
| [0005](0005-postgres-flyway.md) | Postgres + Flyway from day one | Accepted |
```

- [ ] **Step 3: Write Flyway V1 migration**

Create `src/main/resources/db/migration/V1__journal_entries.sql`:

```sql
CREATE TABLE journal_entries (
    id            UUID         PRIMARY KEY,
    occurred_on   DATE         NOT NULL,
    description   VARCHAR(500) NOT NULL,
    currency      CHAR(3)      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id                 UUID         PRIMARY KEY,
    journal_entry_id   UUID         NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    account_code       VARCHAR(64)  NOT NULL,
    side               VARCHAR(6)   NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor_units BIGINT       NOT NULL CHECK (amount_minor_units >= 0),
    sequence_in_entry  INT          NOT NULL,
    UNIQUE (journal_entry_id, sequence_in_entry)
);

CREATE INDEX idx_postings_account_code ON postings (account_code);
CREATE INDEX idx_journal_entries_occurred_on ON journal_entries (occurred_on);
```

- [ ] **Step 4: Verify**

```bash
./mvnw -B -q verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. Flyway hasn't run yet (no test exercises it), but the migration file is on the classpath.

- [ ] **Step 5: Commit**

```bash
git add docs/adr/0005-postgres-flyway.md docs/adr/README.md src/main/resources/db/
git commit -m "$(cat <<'EOF'
feat(persistence): Postgres + Flyway baseline migration V1__journal_entries

Two tables: journal_entries (header) and postings (lines), with FK +
ON DELETE CASCADE. Posting amounts stored as BIGINT minor units with a
non-negative CHECK; side is a CHECK-constrained VARCHAR. Indexes on
account_code (for trial balance) and occurred_on (for date-range
queries in later slices).

Captured in ADR-0005.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: JPA entities and entity ↔ domain mapper

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntity.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/PostingEntity.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java`

- [ ] **Step 1: `JournalEntryEntity.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
class JournalEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "journalEntry",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderColumn(name = "sequence_in_entry")
    private List<PostingEntity> postings = new ArrayList<>();

    protected JournalEntryEntity() {}

    JournalEntryEntity(UUID id, LocalDate occurredOn, String description, String currency) {
        this.id = id;
        this.occurredOn = occurredOn;
        this.description = description;
        this.currency = currency;
    }

    UUID getId() { return id; }
    LocalDate getOccurredOn() { return occurredOn; }
    String getDescription() { return description; }
    String getCurrency() { return currency; }
    List<PostingEntity> getPostings() { return postings; }

    void addPosting(PostingEntity posting) {
        posting.setJournalEntry(this);
        postings.add(posting);
    }
}
```

- [ ] **Step 2: `PostingEntity.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "postings")
class PostingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
    private JournalEntryEntity journalEntry;

    @Column(name = "account_code", nullable = false, length = 64)
    private String accountCode;

    @Column(name = "side", nullable = false, length = 6)
    private String side;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    protected PostingEntity() {}

    PostingEntity(UUID id, String accountCode, String side, long amountMinorUnits) {
        this.id = id;
        this.accountCode = accountCode;
        this.side = side;
        this.amountMinorUnits = amountMinorUnits;
    }

    UUID getId() { return id; }
    String getAccountCode() { return accountCode; }
    String getSide() { return side; }
    long getAmountMinorUnits() { return amountMinorUnits; }
    JournalEntryEntity getJournalEntry() { return journalEntry; }

    void setJournalEntry(JournalEntryEntity journalEntry) {
        this.journalEntry = journalEntry;
    }
}
```

- [ ] **Step 3: `JournalEntryEntityMapper.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.util.Currency;
import java.util.UUID;

/** Translates between the persistence entity graph and the domain model. */
final class JournalEntryEntityMapper {

    private JournalEntryEntityMapper() {}

    static JournalEntryEntity toEntity(JournalEntry entry, UUID id) {
        JournalEntryEntity je = new JournalEntryEntity(
                id,
                entry.occurredOn(),
                entry.description(),
                entry.currency().getCurrencyCode());
        for (Posting p : entry.postings()) {
            je.addPosting(new PostingEntity(
                    UuidV7Generator.create(),
                    p.account().value(),
                    p.side().name(),
                    p.amount().minorUnits()));
        }
        return je;
    }

    static PersistedJournalEntry toDomain(JournalEntryEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        java.util.List<Posting> postings = entity.getPostings().stream()
                .map(pe -> new Posting(
                        new AccountCode(pe.getAccountCode()),
                        Side.valueOf(pe.getSide()),
                        new Money(pe.getAmountMinorUnits(), currency)))
                .toList();
        Result<JournalEntry, JournalError> r =
                JournalEntry.of(entity.getOccurredOn(), entity.getDescription(), postings);
        if (r instanceof Result.Success<JournalEntry, JournalError> s) {
            return new PersistedJournalEntry(new JournalEntryId(entity.getId()), s.value());
        }
        throw new IllegalStateException(
                "Persisted entry failed to reconstitute: "
                        + ((Result.Failure<JournalEntry, JournalError>) r).error());
    }
}
```

The `IllegalStateException` is correct: a persisted entry that won't reconstitute is a true bug (data corruption), not an expected failure. ArchUnit's `noPublicMethodReturnsThrowable` rule is happy — this method returns `PersistedJournalEntry`, not `Throwable`.

- [ ] **Step 4: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(persistence): JPA entities + mapper for journal entries and postings

Package-private entity classes (JournalEntryEntity, PostingEntity)
follow the table layout from V1 migration. JournalEntryEntityMapper
converts intent (JournalEntry) → entity for save and entity →
PersistedJournalEntry for read. Posting IDs are minted by
UuidV7Generator at mapping time.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: `JournalEntryJpaRepository` (Spring Data) and `JpaJournalEntryRepository` (port adapter)

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJpaRepository.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepository.java`

- [ ] **Step 1: Spring Data interface**

Create `JournalEntryJpaRepository.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JournalEntryJpaRepository extends JpaRepository<JournalEntryEntity, UUID> {
}
```

- [ ] **Step 2: Port adapter**

Create `JpaJournalEntryRepository.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed adapter for {@link JournalEntryRepository}. */
@Repository
@Transactional
public class JpaJournalEntryRepository implements JournalEntryRepository {

    private final JournalEntryJpaRepository jpa;

    public JpaJournalEntryRepository(JournalEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PersistedJournalEntry save(JournalEntry entry) {
        var entity = JournalEntryEntityMapper.toEntity(entry, UuidV7Generator.create());
        var saved = jpa.save(entity);
        return JournalEntryEntityMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersistedJournalEntry> findById(JournalEntryId id) {
        return jpa.findById(id.value()).map(JournalEntryEntityMapper::toDomain);
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Verify ArchUnit still passes**

The new code lives in `..infrastructure..` and imports from `..domain..` and `..infrastructure..`. The rule `applicationDoesNotImportInfrastructure` doesn't apply (we're not in application). Run:

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -5
```

Expected: `Tests run: 10, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(persistence): JpaJournalEntryRepository adapter wired through Spring Data

Two-layer split: Spring Data's JournalEntryJpaRepository handles the
SQL; JpaJournalEntryRepository implements the domain port and
delegates, using UuidV7Generator to mint ids at save time.
@Transactional at class level; @Transactional(readOnly = true) on
findById for query-side optimization.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: `JpaJournalEntryRepositoryIT` — Testcontainers integration test

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepositoryIT.java`
- Delete: `src/test/java/co/embracejoy/accounting/keystone/smoke/PlaceholderIT.java` (created in Task 5; superseded)

- [ ] **Step 1: Write the IT**

Create `JpaJournalEntryRepositoryIT.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = co.embracejoy.accounting.keystone.KeystoneApplication.class)
@Testcontainers
@DisplayName("JpaJournalEntryRepository (integration)")
class JpaJournalEntryRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("keystone")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    JpaJournalEntryRepository repository;

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountCode CASH = new AccountCode("1000");
    private static final AccountCode EQUITY = new AccountCode("3000");

    private static JournalEntry validEntry() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(
                LocalDate.parse("2026-05-10"),
                "opening",
                List.of(
                        new Posting(CASH, Side.DEBIT, new Money(10000L, USD)),
                        new Posting(EQUITY, Side.CREDIT, new Money(10000L, USD))));
        return ((Result.Success<JournalEntry, JournalError>) r).value();
    }

    @Test
    @DisplayName("save() persists the entry and returns it with a fresh JournalEntryId")
    void shouldPersistAndReturnPersistedEntryWhenSaving() {
        PersistedJournalEntry persisted = repository.save(validEntry());

        assertThat(persisted.id()).isNotNull();
        assertThat(persisted.id().value()).isNotNull();
        assertThat(persisted.entry().postings()).hasSize(2);
    }

    @Test
    @DisplayName("findById() returns Optional.empty for unknown id")
    void shouldReturnEmptyWhenIdUnknown() {
        Optional<PersistedJournalEntry> found =
                repository.findById(new JournalEntryId(java.util.UUID.randomUUID()));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findById() returns the entry that was saved")
    void shouldRoundTripWhenSavingAndReadingBack() {
        PersistedJournalEntry saved = repository.save(validEntry());

        Optional<PersistedJournalEntry> found = repository.findById(saved.id());

        assertThat(found).isPresent();
        PersistedJournalEntry hydrated = found.get();
        assertThat(hydrated.id()).isEqualTo(saved.id());
        assertThat(hydrated.entry().description()).isEqualTo("opening");
        assertThat(hydrated.entry().currency()).isEqualTo(USD);
        assertThat(hydrated.entry().postings()).hasSize(2);
    }

    @Test
    @DisplayName("UUID v7 ids on saved entries have version 7")
    void shouldUseVersion7UuidWhenSaving() {
        PersistedJournalEntry persisted = repository.save(validEntry());
        assertThat(persisted.id().value().version()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Delete the placeholder IT from Task 5**

```bash
rm src/test/java/co/embracejoy/accounting/keystone/smoke/PlaceholderIT.java
```

- [ ] **Step 3: Run the IT**

This will pull the `postgres:16` image on first run (a few hundred MB; first invocation is slow). Make sure Docker is running.

```bash
./mvnw -B verify 2>&1 | tail -25
```

Expected: BUILD SUCCESS. Output mentions the `JpaJournalEntryRepositoryIT` running 4 tests, all passing. Total tests now ~75 (Phase A) + 4 (this IT) - 1 (placeholder removed) = ~78.

- [ ] **Step 4: Commit**

```bash
git add src/
git rm src/test/java/co/embracejoy/accounting/keystone/smoke/PlaceholderIT.java 2>/dev/null || true
git commit -m "$(cat <<'EOF'
test(persistence): Testcontainers integration test for JpaJournalEntryRepository

Four tests: persists with fresh id, returns empty for unknown id, full
round-trip read-back, ids are UUID v7. Uses @ServiceConnection on the
PostgreSQLContainer for auto-wired JDBC config. Placeholder PlaceholderIT
from Task 5 is removed (Failsafe is now exercised by the real IT).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Convert `noPublicMethodReturnsThrowable` rule to exclude entity classes

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java`

The new JPA entities have package-private constructors and getters; nothing returns Throwable. So this task may be a no-op — but verify.

- [ ] **Step 1: Run the existing ArchUnit tests**

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0`. If all pass, skip the rest of this task and proceed to Phase C.

- [ ] **Step 2: If a rule fails, address it specifically**

(Likely candidate: a rule complaining that `JournalEntryEntity` is in `infrastructure..` but ArchUnit's package whitelist is fine, since `..infrastructure..` is allowed. If a rule does fail, narrow it down before changing — don't weaken correctness.)

- [ ] **Step 3: Commit only if changes**

If you made changes:

```bash
git add src/
git commit -m "$(cat <<'EOF'
test(architecture): adjust ArchUnit rule to accommodate JPA entities

[Specific rule] was unintentionally catching [specific issue]; scoped
[narrowing] without weakening enforcement.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

If no changes, no commit; proceed to Phase C.

---

## Phase B acceptance

After Tasks 9-15:

- Spring Boot 4.0.3 boots with Postgres + Flyway + JPA wired
- `./mvnw -B verify` runs the IT against a Testcontainers Postgres 16; ~78 tests
- `JournalEntryRepository` port is implemented by `JpaJournalEntryRepository`
- ADR-0005 + the V1 migration are committed; ADR README updated

---

# Phase C — Web layer

`POST /journal-entries` controller with Bean Validation on the request DTO, `ResultMapper` translating `JournalError` → RFC 9457 `ProblemDetail`, MockMvc tests for both success and each failure variant.

---

## Task 16: Request and response DTOs

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostJournalEntryRequest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostingRequest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/PostingResponse.java`

- [ ] **Step 1: `PostJournalEntryRequest.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Inbound payload for {@code POST /journal-entries}. */
public record PostJournalEntryRequest(
        @NotNull(message = "occurredOn is required") LocalDate occurredOn,
        @NotBlank(message = "description is required")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,
        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency,
        @NotEmpty(message = "postings must not be empty")
        @Valid
        List<PostingRequest> postings) {

    @JsonCreator
    public PostJournalEntryRequest(
            @JsonProperty("occurredOn") LocalDate occurredOn,
            @JsonProperty("description") String description,
            @JsonProperty("currency") String currency,
            @JsonProperty("postings") List<PostingRequest> postings) {
        this.occurredOn = occurredOn;
        this.description = description;
        this.currency = currency;
        this.postings = postings == null ? List.of() : List.copyOf(postings);
    }
}
```

- [ ] **Step 2: `PostingRequest.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** Inbound posting line within a {@link PostJournalEntryRequest}. */
public record PostingRequest(
        @NotBlank(message = "account is required") String account,
        @NotNull(message = "side is required")
        @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "side must be DEBIT or CREDIT")
        String side,
        @PositiveOrZero(message = "minorUnits must be zero or positive")
        long minorUnits) {
}
```

- [ ] **Step 3: `JournalEntryResponse.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import java.time.LocalDate;
import java.util.List;

/** Outbound representation of a persisted journal entry. */
public record JournalEntryResponse(
        String id,
        LocalDate occurredOn,
        String description,
        String currency,
        List<PostingResponse> postings) {

    public static JournalEntryResponse of(PersistedJournalEntry persisted) {
        List<PostingResponse> postings = persisted.entry().postings().stream()
                .map(JournalEntryResponse::toResponse)
                .toList();
        return new JournalEntryResponse(
                persisted.id().value().toString(),
                persisted.entry().occurredOn(),
                persisted.entry().description(),
                persisted.entry().currency().getCurrencyCode(),
                postings);
    }

    private static PostingResponse toResponse(Posting p) {
        return new PostingResponse(
                p.account().value(), p.side().name(), p.amount().minorUnits());
    }
}
```

- [ ] **Step 4: `PostingResponse.java`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

public record PostingResponse(String account, String side, long minorUnits) {
}
```

- [ ] **Step 5: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Verify ArchUnit**

The new web DTOs reside in `..infrastructure..` and import from `..domain..` and Jakarta Validation/Jackson. Run:

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -5
```

Expected: `Tests run: 10, Failures: 0`.

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): request/response DTOs for journal entries

PostJournalEntryRequest with Bean Validation (NotBlank, NotEmpty,
PositiveOrZero, Pattern for currency and side). JournalEntryResponse
maps from PersistedJournalEntry; ID rendered as the canonical
36-char UUID string. List defensively copied in the request canonical
constructor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: `ResultMapper` (Result.Failure → ProblemDetail)

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapperTest.java`

- [ ] **Step 1: Test (RED)**

Create `ResultMapperTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName("ResultMapper")
class ResultMapperTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    @DisplayName("NoPostings maps to 400 with type URI .../journal/no-postings")
    void shouldMapNoPostingsToProblemDetail() {
        ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.NoPostings());

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getTitle()).isEqualTo("Journal entry has no postings");
        assertThat(pd.getType().toString()).endsWith("/journal/no-postings");
    }

    @Test
    @DisplayName("MixedCurrencies maps to 400 with currencies in detail")
    void shouldMapMixedCurrenciesToProblemDetail() {
        ProblemDetail pd = ResultMapper.toProblemDetail(
                new JournalError.MixedCurrencies(Set.of(USD, Currency.getInstance("EUR"))));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).endsWith("/journal/mixed-currencies");
        assertThat(pd.getDetail()).contains("USD").contains("EUR");
    }

    @Test
    @DisplayName("Unbalanced maps to 400 with debit/credit sums in detail")
    void shouldMapUnbalancedToProblemDetail() {
        ProblemDetail pd = ResultMapper.toProblemDetail(
                new JournalError.Unbalanced(new Money(10000L, USD), new Money(9000L, USD)));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).endsWith("/journal/unbalanced");
        assertThat(pd.getDetail()).contains("10000").contains("9000").contains("USD");
    }

    @Test
    @DisplayName("Overflow maps to 400 with offending side in detail")
    void shouldMapOverflowToProblemDetail() {
        ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.Overflow(Side.DEBIT));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).endsWith("/journal/overflow");
        assertThat(pd.getDetail()).contains("DEBIT");
    }
}
```

- [ ] **Step 2: Verify compile fails**

```bash
./mvnw -B test -Dtest=ResultMapperTest 2>&1 | tail -10
```

Expected: `cannot find symbol class ResultMapper`.

- [ ] **Step 3: Implement `ResultMapper`**

Create `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Translates domain {@link JournalError} variants into RFC 9457
 * {@link ProblemDetail} responses.
 */
public final class ResultMapper {

    private static final String PROBLEM_BASE = "https://embracejoy.co/problems";

    private ResultMapper() {}

    public static ProblemDetail toProblemDetail(JournalError error) {
        return switch (error) {
            case JournalError.NoPostings ignored -> problem(
                    "/journal/no-postings",
                    "Journal entry has no postings",
                    "A journal entry must contain at least one posting.");
            case JournalError.MixedCurrencies mc -> problem(
                    "/journal/mixed-currencies",
                    "Journal entry mixes currencies",
                    "Postings reference multiple currencies: "
                            + mc.currencies().stream()
                                    .map(c -> c.getCurrencyCode())
                                    .sorted()
                                    .toList()
                            + ". Multi-currency journal entries are not supported in this slice.");
            case JournalError.Unbalanced u -> problem(
                    "/journal/unbalanced",
                    "Journal entry is not balanced",
                    "Sum of debits ("
                            + u.debits().minorUnits()
                            + " "
                            + u.debits().currency().getCurrencyCode()
                            + ") does not equal sum of credits ("
                            + u.credits().minorUnits()
                            + " "
                            + u.credits().currency().getCurrencyCode()
                            + ").");
            case JournalError.Overflow o -> problem(
                    "/journal/overflow",
                    "Posting sum overflowed",
                    "Sum of postings on " + o.side() + " side overflowed Long.MAX_VALUE.");
        };
    }

    private static ProblemDetail problem(String path, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create(PROBLEM_BASE + path));
        pd.setTitle(title);
        return pd;
    }
}
```

The `switch` expression on a sealed `JournalError` is exhaustive at compile time — adding a new `JournalError` variant in the future will fail compilation here, exactly the safety property we wanted from the sealed-interface choice in [ADR-0004](docs/adr/0004-result-type-and-problem-details.md).

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=ResultMapperTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Spotless apply + verify**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. Total tests around 82.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): ResultMapper translates JournalError to RFC 9457 ProblemDetail

Exhaustive switch on the sealed JournalError; each variant gets a
stable type URI under https://embracejoy.co/problems/journal/..., a
human-readable title, and a detail string with the relevant context
(currencies, debit/credit sums, offending side). Compiler enforces
exhaustiveness — adding a new error variant will fail this file's
compile until handled.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: `JournalEntryController` + MockMvc test

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

- [ ] **Step 1: Test (RED)**

Create `JournalEntryControllerTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JournalEntryController.class)
@DisplayName("JournalEntryController")
class JournalEntryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PostJournalEntryService service;

    private static final Currency USD = Currency.getInstance("USD");

    private static String validBody() {
        return """
                {
                  "occurredOn": "2026-05-10",
                  "description": "opening",
                  "currency": "USD",
                  "postings": [
                    { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
                  ]
                }
                """;
    }

    private static PersistedJournalEntry validPersisted() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(
                LocalDate.parse("2026-05-10"),
                "opening",
                List.of(
                        new Posting(new AccountCode("1000"), Side.DEBIT, new Money(10000L, USD)),
                        new Posting(new AccountCode("3000"), Side.CREDIT, new Money(10000L, USD))));
        JournalEntry entry = ((Result.Success<JournalEntry, JournalError>) r).value();
        return new PersistedJournalEntry(
                new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000000")),
                entry);
    }

    @Test
    @DisplayName("returns 201 + Location with the new id when service returns Success")
    void shouldReturn201WhenSuccess() throws Exception {
        Mockito.when(service.post(
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList()))
                .thenReturn(Result.success(validPersisted()));

        mvc.perform(post("/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        endsWith("/journal-entries/01902f9f-0000-7000-8000-000000000000")))
                .andExpect(jsonPath("$.id").value("01902f9f-0000-7000-8000-000000000000"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.postings.length()").value(2));
    }

    @Test
    @DisplayName("returns 400 ProblemDetail when service returns Failure(Unbalanced)")
    void shouldReturn400WhenUnbalanced() throws Exception {
        Mockito.when(service.post(
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList()))
                .thenReturn(Result.failure(
                        new JournalError.Unbalanced(new Money(10000L, USD), new Money(9000L, USD))));

        mvc.perform(post("/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("/journal/unbalanced")))
                .andExpect(jsonPath("$.title").value("Journal entry is not balanced"))
                .andExpect(jsonPath("$.detail", containsString("10000")));
    }

    @Test
    @DisplayName("returns 400 ProblemDetail when Bean Validation rejects the request")
    void shouldReturn400WhenValidationFails() throws Exception {
        String invalidBody = """
                {
                  "occurredOn": "2026-05-10",
                  "description": "",
                  "currency": "usd",
                  "postings": []
                }
                """;

        mvc.perform(post("/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.title").value(notNullValue()));
    }
}
```

- [ ] **Step 2: Verify compile fails**

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -10
```

Expected: `cannot find symbol class JournalEntryController`.

- [ ] **Step 3: Implement the controller**

Create `JournalEntryController.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.JournalEntryResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostJournalEntryRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostingRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    private final PostJournalEntryService service;

    public JournalEntryController(PostJournalEntryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request) {
        Currency currency = Currency.getInstance(request.currency());
        List<Posting> postings = request.postings().stream()
                .map(p -> toDomainPosting(p, currency))
                .toList();

        Result<PersistedJournalEntry, ?> result =
                service.post(request.occurredOn(), request.description(), postings);

        return result.fold(
                persisted -> ResponseEntity.created(
                                URI.create("/journal-entries/" + persisted.id().value()))
                        .body(JournalEntryResponse.of(persisted)),
                error -> ResponseEntity.badRequest()
                        .contentType(org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                        .body(ResultMapper.toProblemDetail(
                                (co.embracejoy.accounting.keystone.domain.journal.JournalError)
                                        error)));
    }

    private static Posting toDomainPosting(PostingRequest p, Currency currency) {
        return new Posting(
                new AccountCode(p.account()),
                Side.valueOf(p.side()),
                new Money(p.minorUnits(), currency));
    }
}
```

The wildcard `Result<PersistedJournalEntry, ?>` plus the cast in the error branch is a small wart caused by `PostJournalEntryService.post` returning `Result<PersistedJournalEntry, JournalError>`; we work around the generic by using `?` and casting on the way out. Could alternately be tightened to `Result<PersistedJournalEntry, JournalError>` directly — adjust if Spotless/Checkstyle complain.

- [ ] **Step 4: Verify the test passes**

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -15
```

Expected: `Tests run: 3, Failures: 0`.

If the validation-failure test (Test 3) fails because Spring's default validation error response is not RFC 9457, see Task 19 — we likely need a `@ControllerAdvice` to format Bean Validation failures as ProblemDetails. If so, Task 19 lands in this same task's commit.

- [ ] **Step 5: Spotless + verify**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. Total tests around 85.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): JournalEntryController with POST /journal-entries

Validates the request via Jakarta Validation, maps DTO postings to
domain types, calls PostJournalEntryService, and folds the Result:
Success → 201 + Location + JournalEntryResponse; Failure →
ResultMapper → 400 + application/problem+json. Three MockMvc tests
cover success path, Unbalanced failure, and validation failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 19: `@ControllerAdvice` for Bean Validation → ProblemDetail (only if Task 18 Step 4 needed it)

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ValidationExceptionHandler.java`

Skip this task if Task 18's MockMvc validation test passed without intervention. Spring 6.1+ produces ProblemDetails out of the box for `MethodArgumentNotValidException`, so this may be a no-op. Run Task 18 Step 4 first; only proceed here if it failed.

- [ ] **Step 1: Implement the handler**

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ValidationExceptionHandler {

    private static final String PROBLEM_BASE = "https://embracejoy.co/problems";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handle(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create(PROBLEM_BASE + "/validation"));
        pd.setTitle("Request validation failed");
        return pd;
    }
}
```

- [ ] **Step 2: Re-run Task 18's tests**

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -10
```

Expected: all 3 tests pass.

- [ ] **Step 3: Commit only if changes made**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): ValidationExceptionHandler renders Bean Validation as ProblemDetail

Spring's default MethodArgumentNotValid response wasn't shaped as
RFC 9457 in our test; this @RestControllerAdvice fills the gap with a
"validation" type URI and a detail listing each field error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 20: Web layer ArchUnit rule additions

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java`

The web layer is now substantial; add two rules to keep it bounded.

- [ ] **Step 1: Append two `@ArchTest` fields**

Add these inside `HexagonalArchitectureTest` (alongside the existing rules):

```java
    @ArchTest
    static final ArchRule WEB_DOES_NOT_DEPEND_ON_PERSISTENCE_ENTITIES =
            noClasses()
                    .that().resideInAPackage("..infrastructure.web..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..");

    @ArchTest
    static final ArchRule CONTROLLERS_LIVE_IN_WEB_PACKAGE =
            classes()
                    .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                    .should().resideInAPackage("..infrastructure.web..");
```

- [ ] **Step 2: Verify**

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -5
```

Expected: `Tests run: 12, Failures: 0` (was 10; added 2).

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
test(architecture): add web-layer ArchUnit rules

Prevent web from importing JPA entities directly (must go through
domain types via mappers); ensure all @RestController classes live
under infrastructure.web.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

After Tasks 16-20:

- `POST /journal-entries` is wired end-to-end through MockMvc
- Success returns 201 + Location + JSON body; failures return 400 + ProblemDetail
- 12 ArchUnit rules, all green
- Total tests around 85

---

# Phase D — Observability

Structured JSON logs via `logstash-logback-encoder`, MDC correlation IDs per request, and a custom Prometheus counter + timer for journal entries. Captured in ADR-0008.

---

## Task 21: Logback JSON encoder + dependency

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/logback-spring.xml`

- [ ] **Step 1: Add `logstash-logback-encoder` to `pom.xml`**

In `<properties>` add:

```xml
        <logstash-logback-encoder.version>8.0</logstash-logback-encoder.version>
```

In `<dependencies>` add:

```xml
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>${logstash-logback-encoder.version}</version>
        </dependency>
```

- [ ] **Step 2: `logback-spring.xml`**

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProfile name="!local &amp; !test">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <customFields>{"app":"keystone"}</customFields>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>

    <springProfile name="local | test">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-no-corr}] %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 3: Verify**

```bash
./mvnw -B -q verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/
git commit -m "$(cat <<'EOF'
feat(observability): structured JSON logs (Logstash encoder) for production profile

Default profile emits one JSON object per line via LogstashEncoder
with traceId/spanId/correlationId MDC keys included. Local and test
profiles keep a readable colored console pattern. Static "app" field
distinguishes keystone from siblings in shared log infrastructure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 22: ADR-0008 + MDC correlation ID filter

**Files:**
- Create: `docs/adr/0008-observability.md`
- Modify: `docs/adr/README.md`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/CorrelationIdFilter.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/CorrelationIdFilterTest.java`

- [ ] **Step 1: Write ADR-0008**

```markdown
# ADR-0008: Micrometer + structured JSON logs + MDC correlation IDs

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

Keystone needs to be observable in three dimensions:

1. **Metrics** — RPS, latency percentiles, business counters (entries
   posted, by result), JVM heap, GC pauses.
2. **Logs** — structured (machine-parseable) in production; readable
   pattern in local dev.
3. **Trace correlation** — every log line traceable back to the request
   that produced it, even across asynchronous boundaries.

## Decision

- **Metrics** via Micrometer with the `micrometer-registry-prometheus`
  bridge. `/actuator/prometheus` exposes them. Custom meters live in
  `infrastructure.observability.MetricsConfig`. Naming follows
  Prometheus convention: snake_case, application-prefixed
  (`keystone_journal_entries_posted_total`).
- **Logs** via Logback. Production profile uses
  `logstash-logback-encoder`'s `LogstashEncoder` (one JSON object per
  line). Local + test profiles use a readable colored pattern. Both
  include MDC keys.
- **Correlation** via a `CorrelationIdFilter` (`OncePerRequestFilter`).
  Reads `X-Correlation-Id` from the request if present; otherwise
  generates a UUID v7. Echoes it back as a response header. Sets MDC
  keys `correlationId` and clears them in `finally`.
- **Trace IDs** (`traceId`, `spanId`) are not generated by us; they're
  populated by Spring Boot's autoconfigured Micrometer Tracing if
  enabled. We include them in MDC unconditionally so a future tracing
  add-on works without log changes.

## Consequences

- A single grep/jq pipeline can isolate every log line for a given
  request.
- Custom business metrics live next to the business code, not
  scattered.
- Local dev stays readable; production is machine-parseable.
- We accept the small per-request overhead of generating a correlation
  ID when none is supplied.
- No tracing backend wiring in this slice; that's a Plan 3+ concern.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Replace the 0008 row with:

```
| [0008](0008-observability.md) | Micrometer + structured JSON logs + MDC correlation IDs | Accepted |
```

- [ ] **Step 3: Test (RED)**

Create `CorrelationIdFilterTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    @Test
    @DisplayName("uses incoming X-Correlation-Id header and echoes it back")
    void shouldUseIncomingHeaderWhenPresent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        req.addHeader("X-Correlation-Id", "abc-123");
        FilterChain chain = (request, response) ->
                assertThat(MDC.get("correlationId")).isEqualTo("abc-123");

        new CorrelationIdFilter().doFilter(req, res, chain);

        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("generates a correlation id when none is supplied")
    void shouldGenerateIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (request, response) ->
                assertThat(MDC.get("correlationId")).isNotBlank();

        new CorrelationIdFilter().doFilter(req, res, chain);

        assertThat(res.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("clears MDC even if the filter chain throws")
    void shouldClearMdcWhenChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> {
            throw new RuntimeException("boom");
        };

        try {
            new CorrelationIdFilter().doFilter(req, res, chain);
        } catch (Exception expected) {
            // expected
        }

        assertThat(MDC.get("correlationId")).isNull();
    }
}
```

- [ ] **Step 4: Verify compile fails**

```bash
./mvnw -B test -Dtest=CorrelationIdFilterTest 2>&1 | tail -10
```

Expected: `cannot find symbol class CorrelationIdFilter`.

- [ ] **Step 5: Implement `CorrelationIdFilter`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps each request with a correlation id, echoes it back as a header, and
 * propagates it via MDC for the duration of the request.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming == null || incoming.isBlank())
                ? UuidV7Generator.create().toString()
                : incoming;
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

- [ ] **Step 6: Verify pass**

```bash
./mvnw -B test -Dtest=CorrelationIdFilterTest 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 7: Commit**

```bash
git add docs/adr/0008-observability.md docs/adr/README.md src/
git commit -m "$(cat <<'EOF'
feat(observability): correlation-id filter + ADR-0008

OncePerRequestFilter reads X-Correlation-Id (or mints a UUID v7),
sets MDC.correlationId, echoes the header on the response, and
clears MDC in finally. Three tests: incoming header path, generated
path, exception-safe MDC cleanup. Captured in ADR-0008.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 23: Custom Prometheus counter and timer

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/observability/MetricsConfig.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/observability/MetricsConfigTest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`

- [ ] **Step 1: Add Micrometer Prometheus dependency**

In `<dependencies>`, add:

```xml
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

- [ ] **Step 2: Test (RED)**

Create `MetricsConfigTest.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricsConfig")
class MetricsConfigTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsConfig config = new MetricsConfig();

    @Test
    @DisplayName("registers keystone_journal_entries_posted_total counter for ok and invalid")
    void shouldRegisterCounters() {
        Counter ok = config.journalEntriesPostedOk(registry);
        Counter invalid = config.journalEntriesPostedInvalid(registry);

        ok.increment();
        invalid.increment(2);

        assertThat(registry.get("keystone_journal_entries_posted_total")
                        .tag("result", "ok")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("keystone_journal_entries_posted_total")
                        .tag("result", "invalid")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("registers keystone_journal_entries_post_duration timer")
    void shouldRegisterTimer() {
        Timer timer = config.journalEntriesPostDuration(registry);

        timer.record(java.time.Duration.ofMillis(15));

        assertThat(registry.get("keystone_journal_entries_post_duration").timer().count())
                .isEqualTo(1L);
    }
}
```

- [ ] **Step 3: Verify compile fails**

```bash
./mvnw -B test -Dtest=MetricsConfigTest 2>&1 | tail -10
```

Expected: `cannot find symbol class MetricsConfig`.

- [ ] **Step 4: Implement `MetricsConfig`**

```java
package co.embracejoy.accounting.keystone.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    public static final String COUNTER_POSTED = "keystone_journal_entries_posted_total";
    public static final String TIMER_POST_DURATION = "keystone_journal_entries_post_duration";

    @Bean
    public Counter journalEntriesPostedOk(MeterRegistry registry) {
        return Counter.builder(COUNTER_POSTED)
                .description("Journal entries successfully posted")
                .tag("result", "ok")
                .register(registry);
    }

    @Bean
    public Counter journalEntriesPostedInvalid(MeterRegistry registry) {
        return Counter.builder(COUNTER_POSTED)
                .description("Journal entries rejected for domain failures")
                .tag("result", "invalid")
                .register(registry);
    }

    @Bean
    public Timer journalEntriesPostDuration(MeterRegistry registry) {
        return Timer.builder(TIMER_POST_DURATION)
                .description("Wall-clock duration of POST /journal-entries")
                .register(registry);
    }
}
```

- [ ] **Step 5: Wire counters into the controller**

Modify `JournalEntryController` constructor + `post()` to inject and increment the meters. Replace the existing class with:

```java
package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.JournalEntryResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostJournalEntryRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostingRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    private final PostJournalEntryService service;
    private final Counter postedOk;
    private final Counter postedInvalid;
    private final Timer postDuration;

    public JournalEntryController(
            PostJournalEntryService service,
            Counter journalEntriesPostedOk,
            Counter journalEntriesPostedInvalid,
            Timer journalEntriesPostDuration) {
        this.service = service;
        this.postedOk = journalEntriesPostedOk;
        this.postedInvalid = journalEntriesPostedInvalid;
        this.postDuration = journalEntriesPostDuration;
    }

    @PostMapping
    public ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request) {
        return postDuration.record(() -> handle(request));
    }

    private ResponseEntity<?> handle(PostJournalEntryRequest request) {
        Currency currency = Currency.getInstance(request.currency());
        List<Posting> postings = request.postings().stream()
                .map(p -> toDomainPosting(p, currency))
                .toList();

        Result<PersistedJournalEntry, JournalError> result =
                service.post(request.occurredOn(), request.description(), postings);

        return result.fold(
                persisted -> {
                    postedOk.increment();
                    return ResponseEntity.created(
                                    URI.create("/journal-entries/" + persisted.id().value()))
                            .body(JournalEntryResponse.of(persisted));
                },
                error -> {
                    postedInvalid.increment();
                    return ResponseEntity.badRequest()
                            .contentType(MediaType.parseMediaType("application/problem+json"))
                            .body(ResultMapper.toProblemDetail(error));
                });
    }

    private static Posting toDomainPosting(PostingRequest p, Currency currency) {
        return new Posting(
                new AccountCode(p.account()),
                Side.valueOf(p.side()),
                new Money(p.minorUnits(), currency));
    }
}
```

- [ ] **Step 6: Update `JournalEntryControllerTest` mocks**

The test now requires the three meters as `@MockitoBean`s. Add to the test class:

```java
    @MockitoBean Counter journalEntriesPostedOk;
    @MockitoBean Counter journalEntriesPostedInvalid;
    @MockitoBean Timer journalEntriesPostDuration;
```

And mock the timer's `record(Supplier)` call to invoke the supplier:

```java
        // Add inside each test method, before mvc.perform:
        Mockito.when(journalEntriesPostDuration.record(Mockito.any(java.util.function.Supplier.class)))
                .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(0).get());
```

Run `./mvnw -B test -Dtest=JournalEntryControllerTest` and confirm all 3 tests still pass.

- [ ] **Step 7: Verify everything**

```bash
./mvnw -B spotless:apply
./mvnw -B -q verify
```

Expected: BUILD SUCCESS. Total tests around 90.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/
git commit -m "$(cat <<'EOF'
feat(observability): MetricsConfig + counter/timer increments in controller

Three meters: keystone_journal_entries_posted_total{result=ok|invalid}
and keystone_journal_entries_post_duration. Controller wraps the
service call in Timer.record(Supplier) and increments the relevant
counter in each Result branch. MetricsConfigTest exercises both
counters (ok and invalid) and the timer end-to-end against a
SimpleMeterRegistry.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase D acceptance

After Tasks 21-23:

- Production profile emits structured JSON logs via Logstash encoder
- Every request carries a correlation id (incoming or generated) in MDC + response header
- `keystone_journal_entries_posted_total` and `_post_duration` registered; controller increments them
- ADR-0008 committed; ADR README updated

---

# Phase E — OpenAPI gates

Four-layer gate per design spec §7: generate → Spectral lint → snapshot diff → openapi-diff vs `origin/main`. Snapshot lives at `docs/openapi/openapi.yaml`. Spectral runs via npx (avoids a JVM-side fork; CI installs Node).

---

## Task 24: ADR-0006 + springdoc dependency + `.spectral.yaml`

**Files:**
- Create: `docs/adr/0006-openapi-gates.md`
- Modify: `docs/adr/README.md`
- Modify: `pom.xml`
- Create: `.spectral.yaml`

- [ ] **Step 1: Write ADR-0006**

```markdown
# ADR-0006: OpenAPI four-layer gate

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

REST APIs drift. Without an enforced contract, tomorrow's controller
edit is yesterday's silent breaking change. We want machine-checked
guarantees that:

1. The OpenAPI spec is well-formed.
2. The spec follows internal style rules (operationId, ProblemDetails
   on errors, etc.).
3. Any change to the API surface is visible in the PR diff (no silent
   drift).
4. Breaking changes require explicit human acknowledgement.

## Decision

Four-layer gate, all run as part of `./mvnw verify`:

1. **Layer 1 — generation.** `springdoc-openapi-maven-plugin` boots the
   app and dumps `/v3/api-docs.yaml` to `target/openapi.yaml` during
   the `integration-test` phase. If a controller annotation is
   malformed the plugin fails.
2. **Layer 2 — lint.** Spectral (`@stoplight/spectral-cli` via npx)
   runs against `target/openapi.yaml` with rules in `.spectral.yaml`.
   Style violations fail the build.
3. **Layer 3 — snapshot diff.** `target/openapi.yaml` is diffed against
   the committed `docs/openapi/openapi.yaml`. Non-empty diff fails the
   build with a message instructing the developer to run
   `./mvnw -Popenapi-update verify` and commit the regenerated file.
4. **Layer 4 — breaking-change diff.** `openapi-diff-maven-plugin`
   compares `origin/main:docs/openapi/openapi.yaml` with the PR's
   committed snapshot. Breaking changes fail unless the PR carries
   the label `breaking-change-approved`. Skipped on push to main (no
   base ref). Bound to a Maven profile `openapi-gate` that CI activates;
   local inner loop can skip with `-P!openapi-gate`.

## Consequences

- Every API change is a two-file diff (controller + snapshot) that the
  reviewer sees together.
- Breaking changes require explicit reviewer attention via the label.
- Local builds stay fast; CI does the heavyweight gate.
- Spectral runs out-of-process via npx; CI must install Node. Local dev
  can skip Spectral via the profile if Node isn't present.
- `docs/openapi/openapi.yaml` is hand-maintained-via-regeneration; never
  hand-edited.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Replace the 0006 row:

```
| [0006](0006-openapi-gates.md) | OpenAPI four-layer gate | Accepted |
```

- [ ] **Step 3: Add springdoc dependency**

In `<properties>`:

```xml
        <springdoc.version>2.8.8</springdoc.version>
```

In `<dependencies>`:

```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
```

- [ ] **Step 4: `.spectral.yaml`**

Create at the repo root:

```yaml
extends: ["spectral:oas"]
rules:
  operation-operationId: error
  operation-summary: error
  operation-description: warn
  operation-tag-defined: off
  oas3-schema: error
  no-$ref-siblings: error
  operation-4xx-response: error
  operation-success-response: error
  contact-properties: off
  info-contact: off
  info-license: off
```

- [ ] **Step 5: Verify the springdoc dep loads**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add docs/adr/0006-openapi-gates.md docs/adr/README.md pom.xml .spectral.yaml
git commit -m "$(cat <<'EOF'
feat(openapi): ADR-0006 + springdoc dependency + Spectral ruleset

Captures the four-layer OpenAPI gate decision and lands the
runtime/build prerequisites: springdoc-openapi for the spec
endpoint, .spectral.yaml ruleset (operationId, summary required,
4xx + success responses required, $ref-siblings forbidden).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 25: `springdoc-openapi-maven-plugin` + initial `openapi.yaml` snapshot

**Files:**
- Modify: `pom.xml`
- Create: `docs/openapi/openapi.yaml` (committed snapshot — output of plugin)

- [ ] **Step 1: Add the plugin to `pom.xml`**

In `<properties>`:

```xml
        <springdoc-maven-plugin.version>1.5</springdoc-maven-plugin.version>
```

In `<build><plugins>`:

```xml
            <plugin>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-maven-plugin</artifactId>
                <version>${springdoc-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>generate-openapi</id>
                        <phase>integration-test</phase>
                        <goals><goal>generate</goal></goals>
                    </execution>
                </executions>
                <configuration>
                    <apiDocsUrl>http://localhost:8080/v3/api-docs.yaml</apiDocsUrl>
                    <outputFileName>openapi.yaml</outputFileName>
                    <outputDir>${project.build.directory}</outputDir>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>pre-integration-test</id>
                        <goals><goal>start</goal></goals>
                    </execution>
                    <execution>
                        <id>post-integration-test</id>
                        <goals><goal>stop</goal></goals>
                    </execution>
                </executions>
                <configuration>
                    <profiles>
                        <profile>local</profile>
                    </profiles>
                </configuration>
            </plugin>
```

- [ ] **Step 2: Generate the spec for the first time**

This requires Postgres running locally on `:5434` so the app can boot. Quick start:

```bash
docker run -d --name kp-openapi -p 5434:5432 \
  -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
  -e POSTGRES_DB=keystone postgres:16
sleep 5
./mvnw -B spring-boot:start
sleep 10
curl -fsSL http://localhost:8080/v3/api-docs.yaml -o docs/openapi/openapi.yaml
./mvnw -B spring-boot:stop
docker rm -f kp-openapi
```

(For just this initial commit, the manual approach is simplest. Subsequent regenerations use the Maven lifecycle.)

- [ ] **Step 3: Inspect and commit `docs/openapi/openapi.yaml`**

Open the file and verify it contains a `paths./journal-entries.post` entry and the request/response schemas. Should be ~100 lines.

```bash
git add pom.xml docs/openapi/openapi.yaml
git commit -m "$(cat <<'EOF'
feat(openapi): commit initial OpenAPI snapshot + wire generation plugin

springdoc-openapi-maven-plugin starts the app (via spring-boot
start/stop bound to pre-/post-integration-test) and dumps
target/openapi.yaml. The first snapshot is committed at
docs/openapi/openapi.yaml; future regenerations land via
./mvnw -Popenapi-update verify (Task 26).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 26: Snapshot diff + `-Popenapi-update` profile

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/co/embracejoy/accounting/keystone/api/OpenApiSnapshotTest.java`

- [ ] **Step 1: Add the snapshot diff via a test**

Create `OpenApiSnapshotTest.java`:

```java
package co.embracejoy.accounting.keystone.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@DisplayName("OpenAPI snapshot")
@EnabledIfSystemProperty(named = "openapi.gate", matches = "true")
class OpenApiSnapshotTest {

    @Test
    @DisplayName("generated spec matches the committed snapshot")
    void shouldMatchCommittedSnapshot() throws Exception {
        Path generated = Path.of("target", "openapi.yaml");
        Path committed = Path.of("docs", "openapi", "openapi.yaml");

        assertThat(generated).exists();
        assertThat(committed).exists();

        String generatedContent = normalize(Files.readString(generated));
        String committedContent = normalize(Files.readString(committed));

        assertThat(generatedContent)
                .as("If this fails, the API surface changed. Run "
                        + "`./mvnw -Popenapi-update verify` and commit "
                        + "docs/openapi/openapi.yaml together with your code change.")
                .isEqualTo(committedContent);
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").trim();
    }
}
```

- [ ] **Step 2: Add the `openapi-gate` and `openapi-update` profiles to `pom.xml`**

In `<profiles>` (alongside the `mutation` profile from Task 4):

```xml
        <profile>
            <id>openapi-gate</id>
            <properties>
                <openapi.gate>true</openapi.gate>
            </properties>
        </profile>
        <profile>
            <id>openapi-update</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-antrun-plugin</artifactId>
                        <executions>
                            <execution>
                                <id>copy-generated-openapi-to-snapshot</id>
                                <phase>integration-test</phase>
                                <goals><goal>run</goal></goals>
                                <configuration>
                                    <target>
                                        <copy
                                            file="${project.build.directory}/openapi.yaml"
                                            tofile="${project.basedir}/docs/openapi/openapi.yaml"
                                            overwrite="true"/>
                                    </target>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
```

In the existing surefire/failsafe config, add `<systemPropertyVariables><openapi.gate>${openapi.gate}</openapi.gate></systemPropertyVariables>` so the `@EnabledIfSystemProperty` test runs only when the profile is active.

Actually, simpler: use Failsafe's argLine to forward the system property. Or just rely on Failsafe inheriting the JVM system properties.

The simplest path: the `OpenApiSnapshotTest` uses `@EnabledIfSystemProperty(named = "openapi.gate", matches = "true")`. The `openapi-gate` profile sets the property at the build level. Add to the `failsafe` plugin block:

```xml
                <configuration>
                    <useModulePath>false</useModulePath>
                    <systemPropertyVariables>
                        <openapi.gate>${openapi.gate}</openapi.gate>
                    </systemPropertyVariables>
                </configuration>
```

(With a default of empty string when the profile isn't active — `@EnabledIfSystemProperty` requires a literal "true" match.)

- [ ] **Step 3: Verify**

```bash
./mvnw -B verify -Popenapi-gate 2>&1 | tail -25
```

Expected: BUILD SUCCESS. The test runs (visible in output) and passes (the generated spec matches the committed snapshot just landed in Task 25).

```bash
./mvnw -B verify -Popenapi-update 2>&1 | tail -10
```

Expected: BUILD SUCCESS. The committed snapshot is overwritten from the regenerated one (no-op if no changes).

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/
git commit -m "$(cat <<'EOF'
feat(openapi): snapshot diff test + openapi-gate / openapi-update profiles

OpenApiSnapshotTest runs only with -Popenapi-gate (CI activates it).
On non-empty diff it fails with a clear message telling the dev to
run `./mvnw -Popenapi-update verify` and commit the regenerated
docs/openapi/openapi.yaml. The openapi-update profile uses antrun
to copy target/openapi.yaml over the committed snapshot during
integration-test phase.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 27: Spectral lint + openapi-diff (CI-ish, light local form)

**Files:**
- Modify: `pom.xml`

The Spectral lint and openapi-diff are best run in CI with Node available. Locally, both can be invoked but require either npx or a Maven plugin wrapper. We add Maven plugin invocations bound to the `openapi-gate` profile so CI gets them automatically; local dev inherits them when the profile is active.

- [ ] **Step 1: Add Spectral via `frontend-maven-plugin` and openapi-diff via its Maven plugin**

In `<properties>`:

```xml
        <frontend-maven-plugin.version>1.15.1</frontend-maven-plugin.version>
        <openapi-diff-maven-plugin.version>2.1.0-beta.11</openapi-diff-maven-plugin.version>
```

In the `openapi-gate` `<profile><build><plugins>` block, add:

```xml
                    <plugin>
                        <groupId>com.github.eirslett</groupId>
                        <artifactId>frontend-maven-plugin</artifactId>
                        <version>${frontend-maven-plugin.version}</version>
                        <configuration>
                            <installDirectory>${project.build.directory}/node</installDirectory>
                        </configuration>
                        <executions>
                            <execution>
                                <id>install-node-and-npx</id>
                                <goals><goal>install-node-and-npm</goal></goals>
                                <configuration>
                                    <nodeVersion>v20.18.0</nodeVersion>
                                </configuration>
                            </execution>
                            <execution>
                                <id>spectral-lint</id>
                                <phase>integration-test</phase>
                                <goals><goal>npx</goal></goals>
                                <configuration>
                                    <arguments>--yes @stoplight/spectral-cli@6.13.1 lint --ruleset ${project.basedir}/.spectral.yaml ${project.build.directory}/openapi.yaml</arguments>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.openapitools.openapidiff</groupId>
                        <artifactId>openapi-diff-maven-plugin</artifactId>
                        <version>${openapi-diff-maven-plugin.version}</version>
                        <executions>
                            <execution>
                                <id>openapi-breaking-change-diff</id>
                                <phase>verify</phase>
                                <goals><goal>diff</goal></goals>
                                <configuration>
                                    <oldSpec>https://raw.githubusercontent.com/robsartin/keystone/main/docs/openapi/openapi.yaml</oldSpec>
                                    <newSpec>${project.basedir}/docs/openapi/openapi.yaml</newSpec>
                                    <failOnIncompatible>true</failOnIncompatible>
                                </configuration>
                            </execution>
                        </executions>
                    </plugin>
```

The `failOnIncompatible=true` makes openapi-diff fail the build on breaking changes. The CI workflow (Plan 3) overrides this to allow when the `breaking-change-approved` label is present, or skips the plugin entirely on `push` to main.

- [ ] **Step 2: Verify locally with -Popenapi-gate**

```bash
./mvnw -B verify -Popenapi-gate 2>&1 | tail -25
```

This will:
1. Download Node 20 to `target/node/` (one-time, ~50MB)
2. npx-install Spectral and run it against `target/openapi.yaml`
3. Run openapi-diff fetching the main snapshot from GitHub raw

Expected: BUILD SUCCESS. (On the first run after the initial snapshot lands, openapi-diff will compare main vs main = no diff = pass.)

If the openapi-diff fetch from GitHub raw fails because the file isn't on `main` yet (the initial PR for Plan 2 hasn't merged), you may need to skip layer 4 until the PR opens — comment out the `openapi-diff-maven-plugin` block temporarily and uncomment after the first commit lands on main, OR set `<oldSpec>` to a local path during PR development. Document this caveat in the commit message.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
feat(openapi): Spectral lint + openapi-diff under -Popenapi-gate

frontend-maven-plugin downloads Node 20 and runs Spectral 6.13.1 via
npx against target/openapi.yaml. openapi-diff-maven-plugin fetches
the main snapshot from GitHub raw and compares to the PR's snapshot;
breaking changes fail the build (CI overrides with the
breaking-change-approved label, see Plan 3).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase E acceptance

After Tasks 24-27:

- `./mvnw -B verify -Popenapi-gate` runs all four layers locally
- ADR-0006 + ADR-README updated
- `docs/openapi/openapi.yaml` committed and matches the running app

---

# Phase F — Smoke + finalize

End-to-end smoke test (`@SpringBootTest` + Testcontainers Postgres) that posts an entry, asserts persistence and metric increment; CLAUDE.md / README updates; final cold-cache verify.

---

## Task 28: `ApplicationSmokeIT`

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

- [ ] **Step 1: Write the smoke IT**

```java
package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Application smoke")
class ApplicationSmokeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("keystone")
                    .withUsername("test")
                    .withPassword("test");

    @LocalServerPort int port;
    @Autowired RestClient.Builder rest;

    @Test
    @DisplayName("POST /journal-entries → 201 + counter increment visible at /actuator/prometheus")
    void shouldPostEntryAndIncrementMetric() {
        RestClient client = rest.baseUrl("http://localhost:" + port).build();

        String body = """
                {
                  "occurredOn": "2026-05-10",
                  "description": "smoke",
                  "currency": "USD",
                  "postings": [
                    { "account": "1000", "side": "DEBIT",  "minorUnits": 1234 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 1234 }
                  ]
                }
                """;

        ResponseEntity<String> post = client
                .post()
                .uri("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post.getHeaders().getLocation()).isNotNull();

        String prom = client
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertThat(prom)
                .contains("keystone_journal_entries_posted_total")
                .contains("result=\"ok\"");
    }
}
```

- [ ] **Step 2: Run**

```bash
./mvnw -B verify 2>&1 | tail -20
```

Expected: BUILD SUCCESS. The smoke IT runs against a real Testcontainers Postgres. Total tests around 91.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
test(smoke): ApplicationSmokeIT exercises POST /journal-entries end-to-end

Full @SpringBootTest with random port + Testcontainers Postgres.
Posts a balanced entry, asserts 201 + Location header, then GETs
/actuator/prometheus and asserts the counter is present with
result="ok". Proves the full wiring: web → service → JPA → Postgres
→ Micrometer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 29: README + CLAUDE.md updates + final cold-cache verify

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `README.md`**

Replace the existing `README.md` with:

```markdown
# Keystone

A general ledger built in Spring Boot. This repository is the **keystone** —
the foundation that the rest of the ledger grows from. See
[the foundation design spec](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
for the rationale and the full picture.

## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton (POST /journal-entries, JPA + Postgres + Flyway, observability, OpenAPI gates)
- [ ] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning

## Quick start

```bash
docker run -d --name keystone-pg -p 5434:5432 \
  -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
  -e POSTGRES_DB=keystone postgres:16

./mvnw spring-boot:run

curl -i -X POST http://localhost:8080/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "occurredOn": "2026-05-10",
    "description": "opening balance",
    "currency": "USD",
    "postings": [
      { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
      { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
    ]
  }'
```

## Build

```bash
./mvnw -B verify                  # fast local gate (no PIT, no OpenAPI lint)
./mvnw -B verify -Pmutation       # add PIT mutation coverage (≥60%)
./mvnw -B verify -Popenapi-gate   # add OpenAPI: Spectral lint + snapshot diff + openapi-diff
./mvnw -B verify -Popenapi-update # regenerate docs/openapi/openapi.yaml after an intentional API change
```

CI runs all profiles together: `./mvnw -B verify -Pmutation,openapi-gate`.

## Architecture decisions

See [`docs/adr/`](docs/adr/) — eight ADRs covering hexagonal architecture,
integer money, Result pattern, JUnit 6, Postgres + Flyway, OpenAPI gates,
observability, and the JournalEntryId / PersistedJournalEntry wrapper.

## License

Apache 2.0 — see [LICENSE](LICENSE).
```

- [ ] **Step 2: Update `CLAUDE.md`**

Replace the file with:

```markdown
# CLAUDE.md

Conventions and AI-specific workflow for keystone. For full setup, see
[README.md](README.md).

## Quick reference

- `./mvnw -B verify` — fast local gate (Spotless, Checkstyle, tests, coverage, ArchUnit). PIT and OpenAPI lint skipped.
- `./mvnw -B verify -Pmutation` — adds PIT mutation coverage (≥60% on `domain..` + `application..`).
- `./mvnw -B verify -Popenapi-gate` — adds Spectral lint + snapshot diff + openapi-diff vs main.
- `./mvnw -B verify -Popenapi-update` — regenerates `docs/openapi/openapi.yaml` from the running app (use after intentional API change, then commit).
- `./mvnw spring-boot:run` — run the app (requires Postgres on `localhost:5434`).
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
```

- [ ] **Step 3: Final cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -40
```

Expected: BUILD SUCCESS. Around 91 tests + ArchUnit rules + JaCoCo ≥85% + PIT ≥60% + Spectral clean + snapshot diff clean + openapi-diff clean.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: update README and CLAUDE.md for Plan 2 (Spring Boot walking skeleton)

README documents the full quick-start (docker run + ./mvnw spring-boot:run +
curl) and the four verify modes. CLAUDE.md adds the infrastructure
sub-package structure, the four verify modes, and the new conventions
(typed ids, Postgres+Flyway, OpenAPI snapshot, observability).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase F acceptance

After Tasks 28-29:

- ApplicationSmokeIT proves end-to-end wiring against real Postgres
- README + CLAUDE.md reflect Plan 2's reality
- `./mvnw -B clean verify -Pmutation,openapi-gate` green from cold cache

---

# Plan 2 acceptance (overall)

1. `./mvnw -B clean verify` green from cold cache (no PIT, no OpenAPI lint).
2. `./mvnw -B clean verify -Pmutation,openapi-gate` green from cold cache.
3. JaCoCo line coverage ≥85% on the bundle (likely 95–100%).
4. PIT mutation score ≥60% on `domain..` + `application..` (likely 90–100%).
5. ArchUnit rules pass (12 rules).
6. `docs/openapi/openapi.yaml` matches the live spec.
7. ADRs 0005, 0006, 0008, 0010 committed; ADR-README updated.
8. Issues #3-#10 closed by individual commits in Phase A; #11 closed when this PR merges.
9. PR description references the four-layer OpenAPI gate caveat for the first merge to main (openapi-diff Layer 4 has nothing to compare against until the snapshot is on main; commit it on the first merge then verify Layer 4 on the next PR).




