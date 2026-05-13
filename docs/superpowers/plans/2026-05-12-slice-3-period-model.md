# Slice 3 — Period Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `Period` aggregate keyed by `java.time.YearMonth`, a `PeriodService` with sequential close + most-recent-reopen semantics, and a `PostingInClosedPeriod` validation rule in `JournalEntry.of(...)` that rejects entries whose `occurredOn` falls in a closed period.

**Architecture:** Three phases, one PR per phase. Phase A is purely additive (new `period` package + ADR-0012). Phase B wires the period field into `JournalValidationContext`, the new `JournalError` variant into `JournalEntry.of(...)`, adds Flyway V3 (periods table), the JPA adapter, and `PeriodService`. Phase C adds the REST surface (`PeriodController` + DTOs) and extends the smoke IT.

**Tech Stack:** Same as Slice 2 — Spring Boot 4.0.3 on Java 25, JPA + Postgres + Flyway, Testcontainers, MockMvc. New domain types live in `domain/period/` with zero Spring/JPA dependencies. JPA adapter, controller, and DTOs live under `infrastructure`.

**Pre-condition:** `main` has Slice 2 fully merged (issue #13 closed; PRs #42 + #43 + #44 in). The branch `14-slice-3-phase-a-domain` is checked out, ready for Phase A.

**Spec authority:** `docs/superpowers/specs/2026-05-11-slices-2-3-account-and-period-design.md` (already on `main`). When the spec and this plan disagree, the spec wins.

**Definition of done (entire Slice):**

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every Phase PR and on `main` after each merge.
2. CI's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /periods/2026-06/close` closes June 2026; `POST /periods/2026-06/reopen` reopens it.
4. `GET /periods?status=closed` lists closed periods.
5. `GET /periods/2026-05` returns `{yearMonth: "2026-05", status: "OPEN"}` even with no row in the table (synthesized).
6. `POST /journal-entries` with `occurredOn: "2026-06-15"` when `2026-06` is closed returns 400 + `/problems/journal/posting-in-closed-period`.
7. Re-opening `2026-06` and re-posting the entry succeeds.
8. ADR-0012 committed; ADR README updated.
9. Issue #14 closes when Phase C merges.

---

## File Structure

| Path | Created in | Responsibility |
|---|---|---|
| `docs/adr/0012-period-model-sequential-close.md` | Phase A | ADR: period model + sequential close + audit fields |
| `docs/adr/README.md` | Phase A | flip 0012 to Accepted |
| `src/main/java/.../domain/period/PeriodStatus.java` | Phase A | enum OPEN \| CLOSED |
| `src/main/java/.../domain/period/Period.java` | Phase A | record (yearMonth, status, closedAt, closedBy, reopenedAt, reopenedBy) |
| `src/main/java/.../domain/period/PeriodError.java` | Phase A | sealed: NotSequentiallyClosable, NotMostRecentlyClosed, NotFound |
| `src/main/java/.../domain/period/PeriodRepository.java` | Phase A | port |
| `src/test/java/.../domain/period/PeriodTest.java` | Phase A | record invariant + factory tests |
| `src/test/java/.../domain/period/PeriodErrorTest.java` | Phase A | sealed-interface exhaustiveness sanity |
| `src/main/java/.../domain/journal/JournalError.java` | Phase B | + `PostingInClosedPeriod(YearMonth)` variant |
| `src/main/java/.../domain/journal/JournalValidationContext.java` | Phase B | + `PeriodStatus periodStatus` field; `permissive()` updated |
| `src/main/java/.../domain/journal/JournalEntry.java` | Phase B | `of(ctx)` gets the period-status check; `PostJournalEntryService` calls it |
| `src/test/java/.../domain/journal/JournalEntryTest.java` | Phase B | + 1 new test for `PostingInClosedPeriod` |
| `src/test/java/.../domain/journal/JournalValidationContextTest.java` | Phase B | updated for the new field |
| `src/main/java/.../domain/journal/JournalEntryRepository.java` | Phase B | + `distinctOccurredMonths(): Set<YearMonth>` (for sequential-close check) |
| `src/main/java/.../infrastructure/persistence/journal/JpaJournalEntryRepositoryAdapter.java` | Phase B | implement `distinctOccurredMonths` via JPQL DISTINCT |
| `src/main/resources/db/migration/V3__periods.sql` | Phase B | periods table |
| `src/main/java/.../infrastructure/persistence/period/PeriodEntity.java` | Phase B | JPA entity |
| `src/main/java/.../infrastructure/persistence/period/JpaPeriodRepository.java` | Phase B | Spring Data interface |
| `src/main/java/.../infrastructure/persistence/period/PeriodRepositoryAdapter.java` | Phase B | implements port |
| `src/main/java/.../infrastructure/persistence/period/PeriodEntityMapper.java` | Phase B | entity ↔ domain |
| `src/test/java/.../infrastructure/persistence/period/PeriodRepositoryAdapterIT.java` | Phase B | Testcontainers round-trip |
| `src/main/java/.../application/period/PeriodService.java` | Phase B | use-case service |
| `src/test/java/.../application/period/PeriodServiceTest.java` | Phase B | TDD with fakes |
| `src/main/java/.../application/journal/PostJournalEntryService.java` | Phase B | extended with period lookup |
| `src/main/java/.../infrastructure/config/ApplicationConfig.java` | Phase B | wires PeriodRepository into PostJournalEntryService and PeriodService |
| `src/main/java/.../infrastructure/web/period/PeriodController.java` | Phase C | REST controller |
| `src/main/java/.../infrastructure/web/period/dto/PeriodResponse.java` | Phase C | response DTO |
| `src/main/java/.../infrastructure/web/ResultMapper.java` | Phase C | + `toProblemDetail(PeriodError)` + `PostingInClosedPeriod` in the JournalError switch |
| `src/test/java/.../infrastructure/web/period/PeriodControllerTest.java` | Phase C | MockMvc per endpoint |
| `src/test/java/.../infrastructure/web/ResultMapperTest.java` | Phase C | + 4 tests (3 PeriodError + 1 PostingInClosedPeriod) |
| `src/test/java/.../infrastructure/web/JournalEntryControllerTest.java` | Phase C | + 1 test for `PostingInClosedPeriod` |
| `src/test/java/.../smoke/ApplicationSmokeIT.java` | Phase C | + close-then-post-rejected-then-reopen-then-post-succeeds |
| `docs/openapi/openapi.yaml` | Phase C | regenerated to include `/periods` endpoints |
| `README.md` + `CLAUDE.md` | Phase C | Slice 3 status + Period convention |

---

# Phase A — Domain types + ADR-0012 (purely additive)

Each task is one commit. Phase A doesn't touch `JournalEntry.of(...)`, `PostJournalEntryService`, `JournalError`, or any existing test. Pure additions to `domain/period/`. After Phase A merges, all 173 existing tests still pass.

---

## Task 1: ADR-0012 — Period model

**Files:**
- Create: `docs/adr/0012-period-model-sequential-close.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Write the ADR**

```markdown
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
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Flip the 0012 row to:

```
| [0012](0012-period-model-sequential-close.md) | Period model — fixed monthly, sequential close, audit fields | Accepted |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0012-period-model-sequential-close.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): 0012 period model — fixed monthly, sequential close, audit fields

Calendar-month periods keyed by java.time.YearMonth. Most months don't
have a row (status is implicit OPEN). Sequential close — can only
close X if every earlier month with postings is already closed.
Re-open only the most-recently-closed period. Audit fields default
to "system" until Slice 5 wires auth.

JournalEntry.of() rejects entries whose occurredOn falls in a closed
period (new JournalError.PostingInClosedPeriod variant in Phase B).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `PeriodStatus` enum

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/period/PeriodStatus.java`

No test — trivial two-value enum.

- [ ] **Step 1: Write the enum**

```java
package co.embracejoy.accounting.keystone.domain.period;

/** The lifecycle status of a calendar-month period. */
public enum PeriodStatus {
  OPEN,
  CLOSED
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/period/PeriodStatus.java
git commit -m "$(cat <<'EOF'
feat(domain): PeriodStatus enum (OPEN | CLOSED)

The lifecycle status of a calendar-month period. Most months never
have a Period row — they're implicitly OPEN. A row appears only when
status changes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `Period` record + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/period/PeriodTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/period/Period.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Period")
class PeriodTest {

  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

  @Test
  @DisplayName("rejects null yearMonth")
  void shouldThrowWhenYearMonthIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                null,
                PeriodStatus.OPEN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("rejects null status")
  void shouldThrowWhenStatusIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                MAY_2026,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("rejects null Optional fields")
  void shouldThrowWhenAnyOptionalFieldIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(MAY_2026, PeriodStatus.OPEN, null, Optional.empty(),
                Optional.empty(), Optional.empty()));
  }

  @Test
  @DisplayName("CLOSED requires closedAt and closedBy")
  void shouldThrowWhenClosedWithoutAuditFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Period(
                MAY_2026,
                PeriodStatus.CLOSED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("CLOSED with audit fields is valid")
  void shouldConstructWhenClosedWithAuditFields() {
    Period p =
        new Period(
            MAY_2026,
            PeriodStatus.CLOSED,
            Optional.of(Instant.parse("2026-06-01T09:00:00Z")),
            Optional.of("system"),
            Optional.empty(),
            Optional.empty());
    assertEquals(PeriodStatus.CLOSED, p.status());
    assertEquals("system", p.closedBy().orElseThrow());
  }

  @Test
  @DisplayName("openFor factory returns an OPEN period with no audit fields")
  void shouldReturnOpenFromFactory() {
    Period p = Period.openFor(MAY_2026);
    assertEquals(PeriodStatus.OPEN, p.status());
    assertEquals(MAY_2026, p.yearMonth());
    assertEquals(Optional.empty(), p.closedAt());
    assertEquals(Optional.empty(), p.closedBy());
    assertEquals(Optional.empty(), p.reopenedAt());
    assertEquals(Optional.empty(), p.reopenedBy());
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=PeriodTest 2>&1 | tail -10
```

Expected: `cannot find symbol class Period`.

- [ ] **Step 3: Implement `Period`**

```java
package co.embracejoy.accounting.keystone.domain.period;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * A calendar-month period and its lifecycle status. Most months never have a {@code Period} row
 * persisted; they're implicitly {@link PeriodStatus#OPEN}. A row exists only when the status has
 * been changed at least once.
 */
public record Period(
    YearMonth yearMonth,
    PeriodStatus status,
    Optional<Instant> closedAt,
    Optional<String> closedBy,
    Optional<Instant> reopenedAt,
    Optional<String> reopenedBy) {

  public Period {
    Objects.requireNonNull(yearMonth, "yearMonth");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(closedAt, "closedAt");
    Objects.requireNonNull(closedBy, "closedBy");
    Objects.requireNonNull(reopenedAt, "reopenedAt");
    Objects.requireNonNull(reopenedBy, "reopenedBy");
    if (status == PeriodStatus.CLOSED && (closedAt.isEmpty() || closedBy.isEmpty())) {
      throw new IllegalArgumentException("CLOSED period must have closedAt and closedBy");
    }
  }

  /** Factory for the synthesized "no row exists" OPEN state. */
  public static Period openFor(YearMonth yearMonth) {
    return new Period(
        yearMonth,
        PeriodStatus.OPEN,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=PeriodTest 2>&1 | tail -10
```

Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Period record with audit fields

YearMonth-keyed; OPEN | CLOSED status; four Optional audit fields
(closedAt/By, reopenedAt/By). Constructor validates: non-null
everything, CLOSED requires closedAt and closedBy. The openFor(...)
factory returns the synthesized "no row exists" state used by the
period service when no Period has been persisted for a given month.

Six TDD tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `PeriodError` sealed interface + TDD

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/period/PeriodErrorTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/period/PeriodError.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PeriodError")
class PeriodErrorTest {

  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);
  private static final YearMonth JUNE_2026 = YearMonth.of(2026, 6);

  @Test
  @DisplayName("NotSequentiallyClosable carries attempted and earliest-open-active")
  void notSequentiallyClosableCarriesBoth() {
    PeriodError e = new PeriodError.NotSequentiallyClosable(JUNE_2026, MAY_2026);
    assertInstanceOf(PeriodError.NotSequentiallyClosable.class, e);
    PeriodError.NotSequentiallyClosable n = (PeriodError.NotSequentiallyClosable) e;
    assertEquals(JUNE_2026, n.attempted());
    assertEquals(MAY_2026, n.earliestOpenActive());
  }

  @Test
  @DisplayName("NotMostRecentlyClosed carries attempted and latestClosed")
  void notMostRecentlyClosedCarriesBoth() {
    PeriodError e =
        new PeriodError.NotMostRecentlyClosed(MAY_2026, Optional.of(JUNE_2026));
    PeriodError.NotMostRecentlyClosed n = (PeriodError.NotMostRecentlyClosed) e;
    assertEquals(MAY_2026, n.attempted());
    assertEquals(Optional.of(JUNE_2026), n.latestClosed());
  }

  @Test
  @DisplayName("NotFound carries the queried yearMonth")
  void notFoundCarriesYearMonth() {
    PeriodError e = new PeriodError.NotFound(MAY_2026);
    assertEquals(MAY_2026, ((PeriodError.NotFound) e).yearMonth());
  }

  @Test
  @DisplayName("PeriodError is sealed and lists every variant")
  void sealedListIsComplete() {
    assertEquals(3, PeriodError.class.getPermittedSubclasses().length);
  }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=PeriodErrorTest 2>&1 | tail -10
```

Expected: `cannot find symbol class PeriodError`.

- [ ] **Step 3: Implement `PeriodError`**

```java
package co.embracejoy.accounting.keystone.domain.period;

import java.time.YearMonth;
import java.util.Optional;

/** Errors raised by {@code PeriodService} operations. */
public sealed interface PeriodError {

  /**
   * The requested close would skip earlier periods that still have unclosed postings.
   *
   * @param attempted the YearMonth the caller tried to close
   * @param earliestOpenActive the earliest YearMonth with at least one posting that is still
   *     not closed; the caller should close this one (or one before it) first
   */
  record NotSequentiallyClosable(YearMonth attempted, YearMonth earliestOpenActive)
      implements PeriodError {}

  /**
   * Only the most-recently-closed period can be reopened. The caller tried to reopen a different
   * one (or there are no closed periods at all).
   */
  record NotMostRecentlyClosed(YearMonth attempted, Optional<YearMonth> latestClosed)
      implements PeriodError {}

  /** No persisted Period row for the requested YearMonth (used only by paths that don't synthesize). */
  record NotFound(YearMonth yearMonth) implements PeriodError {}
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=PeriodErrorTest 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): PeriodError sealed type with 3 variants

NotSequentiallyClosable (carries the attempted YearMonth + the
earliest-open-active month the caller should close first),
NotMostRecentlyClosed (with the latestClosed for error context), and
NotFound. Exhaustive sealed switch enforced by the count-pin test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `PeriodRepository` port

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/period/PeriodRepository.java`

No test for the interface; Phase B's `PeriodServiceTest` exercises it via a fake.

- [ ] **Step 1: Write the port**

```java
package co.embracejoy.accounting.keystone.domain.period;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link Period} aggregates. */
public interface PeriodRepository {

  /** Persist a new period (only invoked when the status changes from the synthesized OPEN). */
  Period save(Period period);

  /** Update an existing period's status + audit fields. */
  Period update(Period period);

  /** Look up the persisted row for a YearMonth; absent if no row exists (status is implicitly OPEN). */
  Optional<Period> findByYearMonth(YearMonth yearMonth);

  /** All closed periods, sorted by YearMonth descending. */
  List<Period> findAllClosed();

  /** The latest-closed period (max by YearMonth), if any. */
  Optional<Period> findLatestClosed();
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): PeriodRepository port

save (new row), update (mutate existing), findByYearMonth (Optional —
absent means implicit OPEN), findAllClosed (descending), findLatestClosed.
JPA adapter lands in Phase B.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Phase A acceptance — final verify

**Files:** none

- [ ] **Step 1: Cold-cache verify**

```bash
./mvnw -B clean test 2>&1 | tail -15
```

Expected: BUILD SUCCESS. Total unit tests: 158 (Slice 2 end) + 6 (Period) + 4 (PeriodError) = **168**.

- [ ] **Step 2: Confirm purely additive**

```bash
git diff main..HEAD --stat | head
```

Every line should be additions to new files or to the two existing docs (ADR-0012, ADR README). No changes to `JournalEntry.java`, `JournalError.java`, `JournalValidationContext.java`, or `PostJournalEntryService.java`.

- [ ] **Step 3: No commit; Phase A is done**

Push the branch and open the PR. PR title: `Slice 3 Phase A: period aggregate domain types + ADR-0012`.

---

## Phase A acceptance

6 commits on the branch. All tests pass. No existing code changed. ADR-0012 committed; ADR README updated. The `domain/period/` package now exists, with 4 source files + 2 test files.

**Bootstrap note:** the plan document `2026-05-12-slice-3-period-model.md` lands as part of this branch's first commit (before Task 1). Add it with:

```bash
git add docs/superpowers/plans/2026-05-12-slice-3-period-model.md
git commit -m "$(cat <<'EOF'
docs(plan): Slice 3 — Period model (Phases A-C)

Three phases, three PRs. Phase A is purely additive (Period record,
PeriodStatus, PeriodError, PeriodRepository, ADR-0012). Phase B wires
the period field into JournalValidationContext, adds the
PostingInClosedPeriod JournalError variant, lands Flyway V3, the JPA
adapter, and PeriodService. Phase C adds /periods REST surface and
extends the smoke IT.

Spec authority:
docs/superpowers/specs/2026-05-11-slices-2-3-account-and-period-design.md.
On merge of Phase C, issue #14 closes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase B — Wiring (validation + persistence + application)

Phase B touches existing code: extends `JournalValidationContext`, adds the `PostingInClosedPeriod` `JournalError` variant, gives `JournalEntry.of(...)` the period-status check, adds Flyway V3, the JPA adapter + IT, the `PeriodService` (8 tasks).

---

## Task 7: Extend `JournalError` with `PostingInClosedPeriod`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`

- [ ] **Step 1: Add the variant**

In `JournalError.java`, after the existing variants, add:

```java
  /** Entry's occurredOn falls in a YearMonth that has been closed. */
  record PostingInClosedPeriod(java.time.YearMonth period) implements JournalError {}
```

- [ ] **Step 2: Verify existing tests still pass**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all green; no test for the new variant yet (Task 9 adds them).

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalError gains PostingInClosedPeriod variant

Used by the period-status check in JournalEntry.of() (Task 9).
ResultMapper extension lands in Phase C.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Extend `JournalValidationContext` with `periodStatus`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContext.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalValidationContextTest.java`

Slice 2 Phase B's `JournalValidationContext` has three fields: `accounts`, `nonLeafCodes`, `permissiveMode`. Slice 3 adds a fourth: `periodStatus`.

- [ ] **Step 1: Update the record**

Replace `JournalValidationContext.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry. The application
 * service does the I/O (account + period lookups) and packs results in here.
 */
public record JournalValidationContext(
    Map<AccountCode, Account> accounts,
    Set<AccountCode> nonLeafCodes,
    PeriodStatus periodStatus,
    boolean permissiveMode) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    Objects.requireNonNull(periodStatus, "periodStatus");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  /** Two-arg overload kept for back-compat with non-period callers (defaults to OPEN). */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {
    this(accounts, nonLeafCodes, PeriodStatus.OPEN, false);
  }

  /** Permissive context — skip both account and period checks (used by historical tests). */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of(), Set.of(), PeriodStatus.OPEN, true);
  }
}
```

- [ ] **Step 2: Update `JournalValidationContextTest`**

Update existing tests for the new constructor shape (4 args) and add a test for the period-status field:

```java
  @Test
  @DisplayName("rejects null periodStatus in 4-arg constructor")
  void shouldThrowWhenPeriodStatusIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new JournalValidationContext(Map.of(), Set.of(), null, false));
  }

  @Test
  @DisplayName("two-arg constructor defaults to OPEN, non-permissive")
  void shouldDefaultToOpenWhenTwoArgConstructorUsed() {
    JournalValidationContext ctx = new JournalValidationContext(Map.of(), Set.of());
    assertEquals(PeriodStatus.OPEN, ctx.periodStatus());
    assertEquals(false, ctx.permissiveMode());
  }
```

(plus update import: `import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;`)

The existing emptiness / defensive-copy / unmodifiable-map tests use the 2-arg constructor and continue to pass — the 2-arg overload calls the 4-arg with sensible defaults.

- [ ] **Step 3: Verify**

```bash
./mvnw -B test -Dtest=JournalValidationContextTest 2>&1 | tail -10
```

Expected: all tests pass; count grows by 2.

- [ ] **Step 4: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalValidationContext gains periodStatus field

Adds the fourth field; the existing two-arg constructor defaults to
OPEN + non-permissive, so existing Slice 2 callers (PostJournalEntryService
and tests) continue compiling unchanged. The permissive() factory now
returns a context with OPEN status and permissive=true.

Phase B Task 9 uses this in JournalEntry.of() to reject closed-period
postings.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Extend `JournalEntry.of(ctx)` with the period-status check

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`

Per spec §4.5, the period check happens **before** the per-posting account checks, after the basic NoPostings + MixedCurrencies checks. Order:

1. Postings non-empty *(existing)*
2. Single currency *(existing)*
3. **`ctx.periodStatus()` is OPEN, else `PostingInClosedPeriod(YearMonth.from(occurredOn))` (NEW — Task 9)**
4. Per-posting account validation *(Slice 2)*
5. Overflow *(existing)*
6. Balanced *(existing)*

- [ ] **Step 1: Write the failing test**

Append to `JournalEntryTest.java`:

```java
  @Test
  @DisplayName("of(ctx) returns Failure(PostingInClosedPeriod) when periodStatus is CLOSED")
  void shouldReturnPostingInClosedPeriodWhenPeriodClosed() {
    Account cash =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity =
        new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cash, EQUITY, equity), Set.of(), PeriodStatus.CLOSED, false);
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY,
            "x",
            List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)),
            ctx);
    JournalError.PostingInClosedPeriod e =
        (JournalError.PostingInClosedPeriod)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(YearMonth.from(TODAY), e.period());
  }
```

(plus imports: `import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;` and `import java.time.YearMonth;`)

- [ ] **Step 2: Verify compile + fail**

```bash
./mvnw -B test -Dtest=JournalEntryTest#shouldReturnPostingInClosedPeriodWhenPeriodClosed 2>&1 | tail -10
```

Expected: test FAILS (the period check isn't in `of()` yet).

- [ ] **Step 3: Add the period check in `JournalEntry.of(ctx)`**

In `JournalEntry.of(...)` (the four-arg overload), between the `MixedCurrencies` check and the per-posting loop, insert:

```java
    // Period check — must precede the per-posting account loop.
    if (!ctx.permissiveMode() && ctx.periodStatus() == PeriodStatus.CLOSED) {
      return Result.failure(
          new JournalError.PostingInClosedPeriod(java.time.YearMonth.from(occurredOn)));
    }
```

Add import: `import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;`

- [ ] **Step 4: Verify all tests pass**

```bash
./mvnw -B test 2>&1 | tail -10
```

Expected: all green. JournalEntryTest gains 1 test (16 → 17 total in that file? Adjust depending on Slice 2 count — should be +1 over current).

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntry.of(ctx) rejects postings in closed periods

New check between MixedCurrencies and the per-posting account loop:
if ctx.periodStatus() == CLOSED (and ctx isn't permissive), return
JournalError.PostingInClosedPeriod(YearMonth.from(occurredOn)).
Permissive contexts (historical tests using the 3-arg of() overload)
still skip the check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Add `distinctOccurredMonths()` to `JournalEntryRepository`

Sequential close needs to know which months have postings. Add a small read method to the port.

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepositoryAdapter.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepository.java` (the Spring Data interface)
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JpaJournalEntryRepositoryIT.java`

(Note: file names in your tree may use `JournalEntryRepositoryAdapter` and `JournalEntryJpaRepository` — adjust accordingly. The pattern is identical to Slice 2's account split.)

- [ ] **Step 1: Add the method to the port**

```java
  /**
   * The set of distinct YearMonths that have at least one persisted journal entry.
   * Used by PeriodService to compute "earliest open period with postings" for sequential close.
   */
  java.util.Set<java.time.YearMonth> distinctOccurredMonths();
```

- [ ] **Step 2: Add a derived query on the Spring Data interface**

In the Spring Data repository (e.g., `JournalEntryJpaRepository`), add:

```java
  @org.springframework.data.jpa.repository.Query(
      "SELECT DISTINCT FUNCTION('to_char', e.occurredOn, 'YYYY-MM') FROM JournalEntryEntity e")
  java.util.List<String> findDistinctOccurredMonthStrings();
```

(`to_char(date, 'YYYY-MM')` is the Postgres way to format a date into a "YYYY-MM" string. Using the JPQL `FUNCTION(...)` escape hatch.)

- [ ] **Step 3: Implement in the adapter**

In `JpaJournalEntryRepositoryAdapter` (or whatever the adapter class is named), implement:

```java
  @Override
  @Transactional(readOnly = true)
  public java.util.Set<java.time.YearMonth> distinctOccurredMonths() {
    return jpa.findDistinctOccurredMonthStrings().stream()
        .map(java.time.YearMonth::parse)  // parses "2026-05"
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
```

- [ ] **Step 4: Test it on the existing IT**

Append to `JpaJournalEntryRepositoryIT`:

```java
  @Test
  @DisplayName("distinctOccurredMonths returns YearMonths of all persisted entries")
  void shouldReturnDistinctMonthsForPostings() {
    // Persist three entries: 2026-05, 2026-05, 2026-06.
    repository.save(entryOn(LocalDate.of(2026, 5, 1)));
    repository.save(entryOn(LocalDate.of(2026, 5, 28)));
    repository.save(entryOn(LocalDate.of(2026, 6, 15)));

    Set<YearMonth> months = repository.distinctOccurredMonths();
    assertThat(months).containsExactlyInAnyOrder(YearMonth.of(2026, 5), YearMonth.of(2026, 6));
  }

  // (helper)
  private JournalEntry entryOn(LocalDate d) {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            d,
            "test",
            List.of(
                new Posting(CASH, Side.DEBIT, new Money(1L, USD)),
                new Posting(EQUITY, Side.CREDIT, new Money(1L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }
```

- [ ] **Step 5: Verify**

```bash
./mvnw -B verify -DfailIfNoTests=false 2>&1 | tail -15
```

Expected: BUILD SUCCESS (with local Postgres or via Testcontainers, depending on test setup).

- [ ] **Step 6: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(persistence): JournalEntryRepository.distinctOccurredMonths()

Returns the set of YearMonths with at least one persisted entry.
Used by PeriodService.close() to compute "earliest open period with
postings" for the sequential-close rule.

JPA implementation uses to_char(date, 'YYYY-MM') via JPQL FUNCTION
escape, then parses the string into YearMonth. One new IT round-trip
test covering 3 entries across 2 months.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Flyway V3 — periods table

**Files:**
- Create: `src/main/resources/db/migration/V3__periods.sql`

V3 was reserved for periods during Slice 2.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE periods (
    year_month    CHAR(7)     PRIMARY KEY,
    status        VARCHAR(8)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at     TIMESTAMPTZ,
    closed_by     VARCHAR(200),
    reopened_at   TIMESTAMPTZ,
    reopened_by   VARCHAR(200),
    CONSTRAINT periods_yearmonth_format CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT periods_closed_has_metadata CHECK (
        status = 'OPEN'
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
    )
);
```

- [ ] **Step 2: Verify Flyway runs cleanly**

```bash
./mvnw -B verify 2>&1 | tail -10
```

Expected: BUILD SUCCESS. The IT containers run V3 and create the table.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__periods.sql
git commit -m "$(cat <<'EOF'
feat(persistence): Flyway V3 — periods table

CHAR(7) primary key in "YYYY-MM" format (CHECK constraint enforces
the format). status CHECK over OPEN | CLOSED. CHECK constraint
ensures CLOSED rows carry closed_at + closed_by. No row exists for
an implicitly-OPEN month — the row appears only when status changes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: JPA entity + Spring Data repo + adapter + mapper

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/period/PeriodEntity.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/period/JpaPeriodRepository.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/period/PeriodEntityMapper.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/period/PeriodRepositoryAdapter.java`

- [ ] **Step 1: Write `PeriodEntity`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "periods")
class PeriodEntity {

  @Id
  @Column(name = "year_month", nullable = false, length = 7, updatable = false)
  private String yearMonth;

  @Column(name = "status", nullable = false, length = 8)
  private String status;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "closed_by", length = 200)
  private String closedBy;

  @Column(name = "reopened_at")
  private Instant reopenedAt;

  @Column(name = "reopened_by", length = 200)
  private String reopenedBy;

  protected PeriodEntity() {
    // JPA required no-arg constructor
  }

  PeriodEntity(
      String yearMonth,
      String status,
      Instant closedAt,
      String closedBy,
      Instant reopenedAt,
      String reopenedBy) {
    this.yearMonth = yearMonth;
    this.status = status;
    this.closedAt = closedAt;
    this.closedBy = closedBy;
    this.reopenedAt = reopenedAt;
    this.reopenedBy = reopenedBy;
  }

  String getYearMonth() {
    return yearMonth;
  }

  String getStatus() {
    return status;
  }

  Instant getClosedAt() {
    return closedAt;
  }

  String getClosedBy() {
    return closedBy;
  }

  Instant getReopenedAt() {
    return reopenedAt;
  }

  String getReopenedBy() {
    return reopenedBy;
  }

  void setStatus(String status) {
    this.status = status;
  }

  void setClosedAt(Instant closedAt) {
    this.closedAt = closedAt;
  }

  void setClosedBy(String closedBy) {
    this.closedBy = closedBy;
  }

  void setReopenedAt(Instant reopenedAt) {
    this.reopenedAt = reopenedAt;
  }

  void setReopenedBy(String reopenedBy) {
    this.reopenedBy = reopenedBy;
  }
}
```

- [ ] **Step 2: Write `JpaPeriodRepository`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface JpaPeriodRepository extends JpaRepository<PeriodEntity, String> {

  List<PeriodEntity> findAllByStatusOrderByYearMonthDesc(String status);

  @Query("SELECT p FROM PeriodEntity p WHERE p.status = 'CLOSED' ORDER BY p.yearMonth DESC")
  List<PeriodEntity> findAllClosedDesc();
}
```

- [ ] **Step 3: Write `PeriodEntityMapper`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.time.YearMonth;
import java.util.Optional;

final class PeriodEntityMapper {

  private PeriodEntityMapper() {}

  static PeriodEntity toEntity(Period p) {
    return new PeriodEntity(
        p.yearMonth().toString(),
        p.status().name(),
        p.closedAt().orElse(null),
        p.closedBy().orElse(null),
        p.reopenedAt().orElse(null),
        p.reopenedBy().orElse(null));
  }

  static Period toDomain(PeriodEntity e) {
    return new Period(
        YearMonth.parse(e.getYearMonth()),
        PeriodStatus.valueOf(e.getStatus()),
        Optional.ofNullable(e.getClosedAt()),
        Optional.ofNullable(e.getClosedBy()),
        Optional.ofNullable(e.getReopenedAt()),
        Optional.ofNullable(e.getReopenedBy()));
  }
}
```

- [ ] **Step 4: Write `PeriodRepositoryAdapter`**

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PeriodRepositoryAdapter implements PeriodRepository {

  private final JpaPeriodRepository jpa;

  public PeriodRepositoryAdapter(JpaPeriodRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Period save(Period period) {
    PeriodEntity saved = jpa.save(PeriodEntityMapper.toEntity(period));
    return PeriodEntityMapper.toDomain(saved);
  }

  @Override
  public Period update(Period period) {
    PeriodEntity entity =
        jpa.findById(period.yearMonth().toString())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Period to update does not exist: " + period.yearMonth()));
    entity.setStatus(period.status().name());
    entity.setClosedAt(period.closedAt().orElse(null));
    entity.setClosedBy(period.closedBy().orElse(null));
    entity.setReopenedAt(period.reopenedAt().orElse(null));
    entity.setReopenedBy(period.reopenedBy().orElse(null));
    return PeriodEntityMapper.toDomain(jpa.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Period> findByYearMonth(YearMonth yearMonth) {
    return jpa.findById(yearMonth.toString()).map(PeriodEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Period> findAllClosed() {
    return jpa.findAllClosedDesc().stream().map(PeriodEntityMapper::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Period> findLatestClosed() {
    List<Period> all = findAllClosed();
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }
}
```

- [ ] **Step 5: Verify compile + commit**

```bash
./mvnw -B -q compile 2>&1 | tail -5
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(persistence): PeriodRepositoryAdapter wired through Spring Data

Four files:
- PeriodEntity: JPA entity matching V3 schema; year_month is CHAR(7)
  string ("2026-05" etc.).
- JpaPeriodRepository: Spring Data interface with a JPQL CLOSED-desc
  query.
- PeriodEntityMapper: domain ↔ entity, converting YearMonth ↔ string.
- PeriodRepositoryAdapter: @Repository @Transactional implementing
  the domain port. save() persists new rows; update() mutates
  existing ones in-place via setters.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: `PeriodRepositoryAdapterIT` — Testcontainers integration

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/period/PeriodRepositoryAdapterIT.java`

- [ ] **Step 1: Write the IT**

Pattern is identical to Slice 2's `AccountRepositoryAdapterIT`. Add 5 tests:

1. `shouldRoundTripWhenSavingAndReadingBack` — save an OPEN row, find by yearMonth.
2. `shouldReturnEmptyWhenNoRowForYearMonth` — `findByYearMonth` on an unseeded month returns Optional.empty.
3. `shouldUpdateExistingPeriod` — save OPEN, update to CLOSED with audit fields, re-read, assert values preserved.
4. `shouldReturnAllClosedDescending` — close 2026-05, 2026-06, 2026-07; `findAllClosed` returns them ordered [07, 06, 05].
5. `shouldReturnLatestClosed` — close several; `findLatestClosed` returns the max.

(Full code follows the Slice 2 pattern verbatim; see `AccountRepositoryAdapterIT.java` on `main` for the template.)

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B verify 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(persistence): Testcontainers integration test for PeriodRepositoryAdapter

Five ITs: round-trip save/find, find-on-empty returns Optional.empty,
update from OPEN to CLOSED preserves audit fields, findAllClosed
returns CLOSED rows in descending YearMonth order, findLatestClosed
returns the max.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: `PeriodService` + TDD with fakes

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/period/PeriodServiceTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/period/PeriodService.java`

- [ ] **Step 1: Write `PeriodServiceTest` with fakes**

Use a `FakePeriodRepository` and a `FakeJournalEntryRepository` (existing-style pattern). Eight tests:

1. `shouldCloseWhenSequentiallyValid` — no postings yet, close 2026-05 → Success.
2. `shouldCloseWhenAllEarlierPostingMonthsAreAlreadyClosed` — postings exist in 2026-05 and 2026-06, 2026-05 already closed; close 2026-06 → Success.
3. `shouldRejectCloseWhenEarlierOpenMonthHasPostings` — postings in 2026-05 (open) and 2026-06; close 2026-06 → `NotSequentiallyClosable(attempted=2026-06, earliestOpenActive=2026-05)`.
4. `shouldCloseIdempotentlyWhenAlreadyClosed` — close 2026-05 twice; second call returns Success with the same row (idempotent — no error).
5. `shouldReopenLatestClosed` — close 2026-05; reopen 2026-05 → Success; row has reopenedAt/reopenedBy.
6. `shouldRejectReopenWhenNotMostRecent` — close 2026-05 and 2026-06; reopen 2026-05 → `NotMostRecentlyClosed(attempted=2026-05, latestClosed=Optional.of(2026-06))`.
7. `shouldRejectReopenWhenNoClosedExist` — reopen 2026-05 with no closed periods → `NotMostRecentlyClosed(attempted=2026-05, latestClosed=Optional.empty())`.
8. `shouldSynthesizeOpenWhenNoRowForFindByYearMonth` — `findByYearMonth(2026-05)` returns `Period.openFor(2026-05)` when no row exists.

(Test class skeleton follows Slice 2's `AccountServiceTest` pattern with two inner-class fakes.)

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=PeriodServiceTest 2>&1 | tail -10
```

Expected: `cannot find symbol class PeriodService`.

- [ ] **Step 3: Implement `PeriodService`**

```java
package co.embracejoy.accounting.keystone.application.period;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class PeriodService {

  private final PeriodRepository periods;
  private final JournalEntryRepository journals;

  public PeriodService(PeriodRepository periods, JournalEntryRepository journals) {
    this.periods = Objects.requireNonNull(periods, "periods");
    this.journals = Objects.requireNonNull(journals, "journals");
  }

  public Result<Period, PeriodError> close(YearMonth target, String actor) {
    Optional<Period> existing = periods.findByYearMonth(target);
    if (existing.isPresent() && existing.get().status() == PeriodStatus.CLOSED) {
      return Result.success(existing.get()); // idempotent
    }

    // Compute the earliest open YearMonth that has at least one posting.
    Set<YearMonth> closedMonths =
        periods.findAllClosed().stream()
            .map(Period::yearMonth)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    TreeSet<YearMonth> openActive = new TreeSet<>();
    for (YearMonth m : journals.distinctOccurredMonths()) {
      if (!closedMonths.contains(m)) {
        openActive.add(m);
      }
    }

    YearMonth earliestOpenActive = openActive.isEmpty() ? null : openActive.first();
    if (earliestOpenActive != null && target.isAfter(earliestOpenActive)) {
      return Result.failure(new PeriodError.NotSequentiallyClosable(target, earliestOpenActive));
    }

    Period closed =
        new Period(
            target,
            PeriodStatus.CLOSED,
            Optional.of(Instant.now()),
            Optional.of(actor),
            Optional.empty(),
            Optional.empty());
    return Result.success(existing.isPresent() ? periods.update(closed) : periods.save(closed));
  }

  public Result<Period, PeriodError> reopen(YearMonth target, String actor) {
    Optional<Period> latest = periods.findLatestClosed();
    if (latest.isEmpty() || !latest.get().yearMonth().equals(target)) {
      return Result.failure(
          new PeriodError.NotMostRecentlyClosed(target, latest.map(Period::yearMonth)));
    }
    Period reopened =
        new Period(
            target,
            PeriodStatus.OPEN,
            latest.get().closedAt(),
            latest.get().closedBy(),
            Optional.of(Instant.now()),
            Optional.of(actor));
    return Result.success(periods.update(reopened));
  }

  public Period findByYearMonth(YearMonth target) {
    return periods.findByYearMonth(target).orElseGet(() -> Period.openFor(target));
  }

  public List<Period> findAllClosed() {
    return periods.findAllClosed();
  }
}
```

- [ ] **Step 4: Run + commit**

```bash
./mvnw -B test -Dtest=PeriodServiceTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(application): PeriodService with sequential close + reopen

close(target, actor): idempotent on already-closed; finds the earliest
YearMonth with postings that isn't yet closed (the "earliest open
active") and rejects if target > that month with
PeriodError.NotSequentiallyClosable.

reopen(target, actor): only the most-recently-closed period can be
reopened; else NotMostRecentlyClosed.

findByYearMonth synthesizes an OPEN period via Period.openFor when no
row exists. findAllClosed delegates to the port.

Eight tests with inner-class fakes (FakePeriodRepository,
FakeJournalEntryRepository for the distinctOccurredMonths needs).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Wire `PeriodRepository` into `PostJournalEntryService` + `ApplicationConfig`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`

- [ ] **Step 1: Update `PostJournalEntryService`**

Extend the service to look up the period status for the entry's `YearMonth` and pass it in the context:

```java
package co.embracejoy.accounting.keystone.application.journal;

// ... imports unchanged plus:
import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.time.YearMonth;

public final class PostJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final AccountRepository accountRepository;
  private final PeriodService periodService;

  public PostJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
    this.periodService = Objects.requireNonNull(periodService, "periodService");
  }

  public Result<PersistedJournalEntry, JournalError> post(
      LocalDate occurredOn, String description, List<Posting> postings) {
    Set<AccountCode> codes =
        postings.stream().map(Posting::account).collect(Collectors.toCollection(HashSet::new));
    Map<AccountCode, Account> accounts = accountRepository.findByCodeIn(codes);
    Set<AccountCode> nonLeafCodes =
        accounts.keySet().stream()
            .filter(accountRepository::hasChildren)
            .collect(Collectors.toUnmodifiableSet());
    PeriodStatus periodStatus = periodService.findByYearMonth(YearMonth.from(occurredOn)).status();
    JournalValidationContext ctx =
        new JournalValidationContext(accounts, nonLeafCodes, periodStatus, false);
    return JournalEntry.of(occurredOn, description, postings, ctx).map(journalRepository::save);
  }
}
```

- [ ] **Step 2: Update `PostJournalEntryServiceTest`**

Add a `FakePeriodService` (or use a stub that returns `Period.openFor(...)` for everything). Update the constructor calls. Add 1 new test verifying that when the period is closed, the service propagates `PostingInClosedPeriod`. (The existing tests with a default-open fake continue to pass.)

- [ ] **Step 3: Update `ApplicationConfig`**

Wire `PeriodService` and update the `PostJournalEntryService` bean:

```java
  @Bean
  public PeriodService periodService(
      PeriodRepository periodRepository, JournalEntryRepository journalRepository) {
    return new PeriodService(periodRepository, journalRepository);
  }

  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService) {
    return new PostJournalEntryService(journalRepository, accountRepository, periodService);
  }
```

- [ ] **Step 4: Run full verify**

```bash
./mvnw -B verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(application): PostJournalEntryService consults PeriodService for status

Wires PeriodService into PostJournalEntryService. The service now
looks up the entry's YearMonth via periodService.findByYearMonth(...)
(which synthesizes OPEN when no row exists) and passes the status
into JournalValidationContext alongside the account map. JournalEntry.of(...)
takes it from there.

ApplicationConfig wires the new PeriodService bean and updates the
PostJournalEntryService bean definition.

Existing PostJournalEntryServiceTest tests get a FakePeriodService
that returns OPEN for everything; one new test exercises the
closed-period rejection path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: Phase B acceptance — full verify

**Files:** none

- [ ] **Step 1: Clean cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -30
```

Expected: BUILD SUCCESS. Coverage holds; mutation kill rate stays ≥ 60%.

- [ ] **Step 2: No commit; Phase B is done**

Push `14-slice-3-phase-b-wiring` and open the PR.

---

## Phase B acceptance

10 commits (7 through 15, plus a no-op verify). All gates green. Period JPA adapter + service + V3 migration live. `JournalEntry.of(ctx)` rejects closed-period postings. `PostJournalEntryService` consults `PeriodService` for status.

---

# Phase C — Web layer + smoke

---

## Task 17: `PeriodResponse` DTO

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/period/dto/PeriodResponse.java`

- [ ] **Step 1: Write the DTO**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.period.dto;

import co.embracejoy.accounting.keystone.domain.period.Period;
import java.time.Instant;

public record PeriodResponse(
    String yearMonth,
    String status,
    Instant closedAt,
    String closedBy,
    Instant reopenedAt,
    String reopenedBy) {

  public static PeriodResponse of(Period p) {
    return new PeriodResponse(
        p.yearMonth().toString(),
        p.status().name(),
        p.closedAt().orElse(null),
        p.closedBy().orElse(null),
        p.reopenedAt().orElse(null),
        p.reopenedBy().orElse(null));
  }
}
```

No request DTO needed — `close` and `reopen` take only the path variable; no body.

- [ ] **Step 2: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(web): PeriodResponse DTO

Simple flat response shape: yearMonth string, status, four nullable
audit fields. of(Period) factory unwraps Optionals to nullable JSON
fields.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: `PeriodController` + MockMvc tests

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/period/PeriodControllerTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/period/PeriodController.java`

Endpoints (per spec §6.2):
- `GET /periods?status=closed` → list (200 + array; only CLOSED rows; OPEN is implicit)
- `GET /periods/{yyyy-mm}` → single (200 + PeriodResponse; synthesizes OPEN if no row)
- `POST /periods/{yyyy-mm}/close` → 200 + PeriodResponse / 400 + ProblemDetail
- `POST /periods/{yyyy-mm}/reopen` → 200 + PeriodResponse / 400 + ProblemDetail

Path-variable format: `^\d{4}-(0[1-9]|1[0-2])$`. Use Spring's regex constraint via `@PathVariable @Pattern(...)`. Malformed paths get `MethodArgumentNotValidException` → existing `/problems/validation` ProblemDetail (no change to ValidationExceptionHandler needed; Spring auto-handles `ConstraintViolationException` similarly with `application/problem+json` after configuration — see existing pattern in Slice 2's AccountController, which uses a regex pattern on the same shape `^[A-Z]{3}$`).

- [ ] **Step 1: Write tests covering all four endpoints + every failure variant**

Pattern follows `AccountControllerTest` exactly: `@WebMvcTest(PeriodController.class)`, `@MockitoBean PeriodService`. Eight tests:

1. `getClosedPeriods` returns list.
2. `getByYearMonth_closed` returns 200 + CLOSED row.
3. `getByYearMonth_implicitOpen` returns 200 + synthesized OPEN.
4. `close_success` returns 200.
5. `close_idempotent` returns 200 on re-close.
6. `close_notSequentiallyClosable` returns 400 + `/problems/period/not-sequentially-closable`.
7. `reopen_success` returns 200.
8. `reopen_notMostRecentlyClosed` returns 400 + `/problems/period/not-most-recently-closed`.

- [ ] **Step 2: Implement `PeriodController`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.period;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.period.dto.PeriodResponse;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/periods")
@Validated
public class PeriodController {

  private static final String YEAR_MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";
  private static final String ACTOR_DEFAULT = "system";

  private final PeriodService service;

  public PeriodController(PeriodService service) {
    this.service = service;
  }

  @GetMapping
  public List<PeriodResponse> list(
      @RequestParam(value = "status", required = false) String status) {
    if (status != null && status.equalsIgnoreCase("closed")) {
      return service.findAllClosed().stream().map(PeriodResponse::of).toList();
    }
    return List.of(); // OPEN periods are implicit; not enumerable
  }

  @GetMapping("/{yyyymm}")
  public ResponseEntity<?> get(@PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Period p = service.findByYearMonth(YearMonth.parse(yyyymm));
    return ResponseEntity.ok(PeriodResponse.of(p));
  }

  @PostMapping("/{yyyymm}/close")
  public ResponseEntity<?> close(@PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Result<Period, PeriodError> r = service.close(YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  @PostMapping("/{yyyymm}/reopen")
  public ResponseEntity<?> reopen(@PathVariable("yyyymm") @Pattern(regexp = YEAR_MONTH_PATTERN) String yyyymm) {
    Result<Period, PeriodError> r = service.reopen(YearMonth.parse(yyyymm), ACTOR_DEFAULT);
    return r.fold(p -> ResponseEntity.ok(PeriodResponse.of(p)), this::error);
  }

  private ResponseEntity<ProblemDetail> error(PeriodError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
```

- [ ] **Step 3: Verify all controller tests pass + apply Spotless + commit**

```bash
./mvnw -B test -Dtest=PeriodControllerTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): PeriodController with 4 endpoints

GET /periods?status=closed lists the CLOSED rows (OPEN is implicit
and not enumerable). GET /periods/{yyyy-mm} returns the synthesized
or persisted state. POST /periods/{yyyy-mm}/close and /reopen are
the lifecycle endpoints. Path-variable regex enforces YYYY-MM format;
malformed paths render as /problems/validation via the existing
ValidationExceptionHandler.

Eight MockMvc tests cover happy paths + every failure variant
through ResultMapper.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 19: Extend `ResultMapper` for `PeriodError` + `PostingInClosedPeriod`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapperTest.java`

- [ ] **Step 1: Add `toProblemDetail(PeriodError)`**

```java
  public static ProblemDetail toProblemDetail(PeriodError err) {
    return switch (err) {
      case PeriodError.NotSequentiallyClosable n -> problem(
          HttpStatus.BAD_REQUEST,
          "/period/not-sequentially-closable",
          "Period close is out of order",
          "Cannot close "
              + n.attempted()
              + "; close "
              + n.earliestOpenActive()
              + " (or an earlier month) first.");
      case PeriodError.NotMostRecentlyClosed n -> problem(
          HttpStatus.BAD_REQUEST,
          "/period/not-most-recently-closed",
          "Period reopen requires the most-recently-closed period",
          "Cannot reopen "
              + n.attempted()
              + n.latestClosed().map(lc -> "; latest closed is " + lc).orElse("; no closed periods"));
      case PeriodError.NotFound nf -> problem(
          HttpStatus.NOT_FOUND,
          "/period/not-found",
          "Period not found",
          "No period row for " + nf.yearMonth() + ".");
    };
  }
```

- [ ] **Step 2: Extend the `JournalError` switch with `PostingInClosedPeriod`**

```java
      case JournalError.PostingInClosedPeriod p -> problem(
          HttpStatus.BAD_REQUEST,
          "/journal/posting-in-closed-period",
          "Posting falls in a closed period",
          "Period " + p.period() + " is closed; reopen it or change the entry's occurredOn date.");
```

- [ ] **Step 3: Extend `ResultMapperTest` with 4 new tests**

Pattern matches the existing AccountError tests: assert status, title, type URI suffix, and detail contents.

- [ ] **Step 4: Run + commit**

```bash
./mvnw -B test 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(web): ResultMapper handles PeriodError + PostingInClosedPeriod

Three new PeriodError → ProblemDetail mappings under
/problems/period/* (not-sequentially-closable, not-most-recently-closed,
not-found) plus one new JournalError mapping under
/journal/posting-in-closed-period. Four new ResultMapperTest cases.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 20: Extend `JournalEntryControllerTest` for `PostingInClosedPeriod`

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

- [ ] **Step 1: Add one new test**

Same pattern as the four Slice-2 account-failure tests: mock `PostJournalEntryService.post(...)` to return `Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 5)))`; assert 400 + `application/problem+json` + `/problems/journal/posting-in-closed-period` + detail contains "2026-05".

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B test -Dtest=JournalEntryControllerTest 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(web): JournalEntryControllerTest covers PostingInClosedPeriod

One new test asserts 400 + application/problem+json +
/problems/journal/posting-in-closed-period when the service returns
Result.failure(PostingInClosedPeriod).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 21: Extend `ApplicationSmokeIT` with period lifecycle

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

- [ ] **Step 1: Add a new test method**

Add a single test that exercises the full lifecycle:

1. Post a balanced entry against the seeded accounts with `occurredOn: 2026-06-15` → assert 201.
2. `POST /periods/2026-06/close` → assert 200 + status=CLOSED.
3. Post the same entry again → assert 400 + `/problems/journal/posting-in-closed-period`.
4. `POST /periods/2026-06/reopen` → assert 200 + status=OPEN with `reopenedAt` populated.
5. Post the same entry again → assert 201.

Use `RestClient` (the same pattern as Slice 2's smoke). Keep methods under the 30-line Checkstyle limit — split into helpers if needed.

- [ ] **Step 2: Run + commit**

```bash
./mvnw -B verify 2>&1 | tail -10
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(smoke): ApplicationSmokeIT exercises period close/reopen lifecycle

Full happy-path scenario: post entry → close period → post rejected
→ reopen period → post succeeds. Proves the close-then-reopen flow
end-to-end against real Postgres.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 22: README + CLAUDE.md updates + regenerate OpenAPI snapshot + final verify

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/openapi/openapi.yaml`

- [ ] **Step 1: Regenerate OpenAPI snapshot**

Start local Postgres if needed:

```bash
docker run -d --name kp-pg -p 5434:5432 \
  -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
  -e POSTGRES_DB=keystone postgres:16
sleep 6
./mvnw -B verify -Popenapi-update -Dopenapi.diff.skip=true 2>&1 | tail -10
docker rm -f kp-pg
```

The committed `docs/openapi/openapi.yaml` now includes `/periods` endpoints. Check the diff to confirm `paths./periods` and `paths./periods/{yyyymm}/*` appear.

- [ ] **Step 2: Update `README.md` Status**

Flip Slice 3 to ✅:

```markdown
- [x] Slice 3 — period model (#14)
```

- [ ] **Step 3: Update `CLAUDE.md` Key Conventions**

Add a bullet for the period model:

```markdown
- **Periods are calendar-month, sequentially closed.** `Period` keyed by `java.time.YearMonth`; most months never have a row (status is implicit `OPEN`). Closing must happen from the earliest open month with postings; reopening only the most-recently-closed. `JournalEntry.of(...)` rejects postings in a `CLOSED` period via the `JournalValidationContext`. See [ADR-0012](docs/adr/0012-period-model-sequential-close.md).
```

- [ ] **Step 4: Final cold-cache verify**

```bash
./mvnw -B clean verify -Pmutation,openapi-gate 2>&1 | tail -30
```

Expected: BUILD SUCCESS with all gates.

- [ ] **Step 5: Apply Spotless and commit (closes #14)**

```bash
./mvnw -B spotless:apply
git add docs/openapi/openapi.yaml README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: Slice 3 done — flip status, add period convention, regenerate OpenAPI

README's Status section flips Slice 3 to ✅. CLAUDE.md Key Conventions
gets a bullet for the period model + ADR-0012 link.

docs/openapi/openapi.yaml regenerated to include /periods endpoints
(GET, GET/{yyyymm}, POST/{yyyymm}/close, POST/{yyyymm}/reopen).

Closes #14

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

6 new commits (17 through 22). PeriodController surface live; `ResultMapper` covers every new variant; smoke IT exercises the full close/reopen lifecycle; README + CLAUDE.md updated; OpenAPI snapshot regenerated. `Closes #14` in the final commit.

---

## Slice 3 overall acceptance

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every Phase PR and on `main` after each merge.
2. CI's `docker` job continues to publish `ghcr.io/robsartin/keystone:latest` on push to main.
3. `POST /periods/2026-06/close` closes June 2026; `POST /periods/2026-06/reopen` reopens it.
4. `GET /periods/2026-05` returns the synthesized OPEN state when no row exists.
5. `POST /journal-entries` with `occurredOn` in a closed period returns 400 + `/problems/journal/posting-in-closed-period`.
6. ADR-0012 committed; ADR README updated; OpenAPI snapshot regenerated to include `/periods` endpoints.
7. Issue #14 closes when Phase C merges.
