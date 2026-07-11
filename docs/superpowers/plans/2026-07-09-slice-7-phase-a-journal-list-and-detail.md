# Slice 7 Phase A — journal list + detail API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Amendment 1 (2026-07-09) — read before executing

Three plan gaps caught after merge. Apply these overrides throughout:

### A1.1 — `JournalEntry` construction

The plan's tests use `JournalEntry.raw(LocalDate, String, List<Posting>)` — that method does **not** exist. `JournalEntry` is a record with canonical constructor `(TenantId tenantId, LocalDate occurredOn, String description, List<Posting> postings)`. Two implications:

- Test-side entry construction uses either the canonical constructor directly OR `JournalEntry.of(tenantId, occurredOn, description, postings, JournalValidationContext.permissive())` (returns `Result` — unwrap via `.orElseThrow(...)` in test setup). Read `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java` for the actual convention.
- `JournalEntry.reverse(...)` takes the original entry's tenant from `original.tenantId()`. Signature and body:

```java
public static JournalEntry reverse(
    JournalEntryId originalId, String reason, java.time.LocalDate today, JournalEntry original) {
  Objects.requireNonNull(originalId, "originalId");
  Objects.requireNonNull(reason, "reason");
  Objects.requireNonNull(today, "today");
  Objects.requireNonNull(original, "original");
  if (reason.isBlank()) {
    throw new IllegalArgumentException("reason must not be blank");
  }
  List<Posting> swapped =
      original.postings().stream()
          .map(p -> new Posting(p.account(), p.side().opposite(), p.amount(), p.baseAmount()))
          .toList();
  String description = "Reversal of #" + originalId.value() + ": " + reason;
  return new JournalEntry(original.tenantId(), today, description, swapped);
}
```

No `Result` return — derived state from validated input; the canonical constructor's null/copyOf checks are sufficient. `Side.opposite()` doesn't exist yet; T1 adds it (Amendment A1.4).

### A1.2 — V10 migration grows two more columns

`ReversedByMetadata` needs `reversedAt` (Instant) and `reversedBy` (String). Neither exists on `journal_entries` (only `created_at` — row insertion timestamp, no semantic "posted by" field). V10 SQL becomes:

```sql
ALTER TABLE journal_entries
  ADD COLUMN reverses_id UUID NULL,
  ADD COLUMN reversal_reason TEXT NULL,
  ADD COLUMN posted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN posted_by TEXT NULL;

ALTER TABLE journal_entries
  ADD CONSTRAINT journal_entries_reverses_fk
  FOREIGN KEY (tenant_id, reverses_id)
  REFERENCES journal_entries (tenant_id, id);

CREATE INDEX journal_entries_reverses_idx
  ON journal_entries (tenant_id, reverses_id);
```

`posted_at` is `NOT NULL DEFAULT now()` so existing rows backfill without a data migration. `posted_by` is nullable — historical rows have no known actor; new rows (after this migration) populate from the JWT sub via the actor propagation change in Amendment A1.5.

### A1.3 — `JournalEntryRepository` port gains two methods

The plan added `saveReversal(...)` to the adapter class only. But `application/journal/ReverseJournalEntryService` (Phase B) can only depend on the domain port (ArchUnit's `APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE`). Both `existsReversalOf` and `saveReversal` land on the port in Phase A T2:

```java
// src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java
public interface JournalEntryRepository {
  // existing:
  PersistedJournalEntry save(JournalEntry entry, String actor);           // signature change — see A1.5
  Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id);
  Set<YearMonth> distinctOccurredMonths(TenantId tenantId);
  // NEW in T2:
  PersistedJournalEntry saveReversal(JournalEntry reversal, ReversalMetadata metadata, String actor);
  boolean existsReversalOf(TenantId tenantId, JournalEntryId originalId);
}
```

`saveReversal` is exercised end-to-end in Phase B; T2 covers the round-trip in the adapter IT.

### A1.4 — Add `Side.opposite()` in T1

The reverse factory (A1.1) uses `Side.opposite()`. Doesn't exist. Add:

```java
// src/main/java/co/embracejoy/accounting/keystone/domain/journal/Side.java
public Side opposite() {
  return this == DEBIT ? CREDIT : DEBIT;
}
```

Test in `JournalEntryReversalTest` (or a small `SideTest`) — one assertion per direction is enough.

### A1.5 — Propagate `actor` through the write path in T2

`posted_by` must populate at save time. Existing `JournalEntryController.create(...)` and `PostJournalEntryService.post(...)` don't carry an actor. Fold into T2:

- `PostJournalEntryService.post(...)` and `JournalEntryRepository.save(...)` gain a `String actor` argument.
- `JournalEntryController.create(...)` reads it from `SecurityContextHolder.getContext().getAuthentication().getName()` — same pattern as `UserRoleUiController.grant(...)` in Slice 5.
- `JournalEntryRepositoryAdapter.save(...)` writes the actor to the new `posted_by` column.
- Existing `JournalEntryControllerTest` cases use `.with(withTestAuth(...))` — they already have a JWT sub in the SecurityContext, so the tests should still pass; just add one assertion in the round-trip IT that `posted_by` matches the actor sub.
- Existing `ApplicationSmokeIT` passes `smoke-user` as the JWT sub → `posted_by = "smoke-user"` on every entry it writes. Not exercised in a smoke assertion, just documented behavior.

### A1.6 — `ReversedByMetadata` populated from real columns in T5

T5 step 3's LEFT JOIN pulls `r.posted_at` and `r.posted_by` (real after A1.2) into `ReversedByMetadata`. Delete the fallback caveat about non-existent columns.

---

Everything below is the original merged plan. Apply the amendments above wherever they touch a task.

---

**Goal:** Ship migration V10 + persistence extensions + read model + `GET /journal-entries` (list, cursor + filters) + `GET /journal-entries/{id}` (detail with reversal metadata). Phase A of Slice 7; the reverse endpoint lands in Phase B, the UI in Phase C.

**Architecture:** Add nullable `reverses_id` + `reversal_reason` columns to `journal_entries` (V10, composite FK preserving tenant isolation). Domain gains an unused `reverse()` factory and a `NotFound` error variant. Persistence adapter round-trips the new columns. A new `JournalEntryReadModel` port (JdbcClient adapter, per the `TrialBalanceReadModel` precedent from Slice 4) serves the list + detail queries; cursor pagination uses `id > cursor` (UUID v7 sorts by time). Controller gains two `@GetMapping` endpoints; OpenAPI snapshot regen closes the phase.

**Tech Stack:** Java 25, Spring Boot 4.0.3, Postgres 16 via Testcontainers, Flyway, Spring `JdbcClient`, JUnit Jupiter 6, ArchUnit 1.4.1.

## Global Constraints

- **Java 25**, Spring Boot 4.0.3, Maven wrapper `./mvnw`.
- **Hexagonal layering** (ADR-0002, ArchUnit-enforced): `domain` imports nothing outside `java.*` and own packages; `application` depends on `domain` only; `infrastructure` depends on both.
- **`Result<T, E>` for internal errors** (ADR-0004); no `throws` on public methods in `..application..` (enforced by `ApplicationDoesNotThrowArchTest`).
- **Typed IDs** (ADR-0010): no raw `java.util.UUID` fields in `..domain..` classes whose simple name doesn't end with `Id` (enforced by `DomainUsesTypedIdsArchTest`).
- **Money as `long` minor units** (ADR-0003); no `double`/`float`/`BigDecimal` fields in `..domain..`.
- **OpenAPI committed snapshot** (ADR-0006). Any controller change to the JSON API surface pairs with a regenerated `docs/openapi/openapi.yaml`.
- **TDD**: red → green → refactor → commit. Every step below alternates.
- **Google Java Format** via Spotless (`./mvnw spotless:apply`). Checkstyle: 750-line file max, 30-line method max, no star imports.
- **Tests use `@DisplayName`** and method names `should<Expected>When<Condition>`.
- **JaCoCo gate at 85% line coverage**; PIT 60% mutation on `domain..` + `application..`.
- **Cursor pagination** uses `id > cursor` (UUID v7 sorts by time, so this doubles as chronological order).
- **SQL amount filters** must be in `HAVING SUM(...)` (aggregation), not `WHERE p.debit_minor >=` — a large-total entry with a small-value single posting must NOT pass a low `amountMin`.
- **Never commit direct to main.** PR-based workflow on branch `slice-7-phase-a-list-detail`.
- **Testcontainers `@ServiceConnection`** on new ITs (existing convention, e.g. `ApplicationSmokeIT`).

---

## Files

### Create (production)

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V10__journal_entry_reversal.sql` | ADD COLUMN `reverses_id` UUID NULL + `reversal_reason` TEXT NULL; composite FK `(tenant_id, reverses_id) → journal_entries(tenant_id, id)`; index on `(tenant_id, reverses_id)`. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversalMetadata.java` | Immutable record `(JournalEntryId reversesId, String reason)`. Stored on `PersistedJournalEntry` when this entry IS a reversal. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversedByMetadata.java` | Immutable record `(JournalEntryId reversalId, Instant reversedAt, String reversedBy, String reason)`. Derived from LEFT JOIN when this entry HAS BEEN reversed. Read-model only; not stored. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQuery.java` | Record `(Optional<LocalDate> from, Optional<LocalDate> to, Optional<AccountCode> account, Optional<String> q, Optional<Long> amountMin, Optional<Long> amountMax, Optional<JournalEntryId> after, int limit)`. Filter shape for the list surface. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReadModel.java` | Port interface with `findMany(TenantId, JournalEntryQuery) → JournalEntryPage` and `findById(TenantId, JournalEntryId) → Optional<PersistedJournalEntry>`. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryPage.java` | Record `(List<PersistedJournalEntry> items, Optional<JournalEntryId> nextCursor)`. |
| `src/main/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryService.java` | Thin application service delegating to the read model. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModel.java` | JdbcClient adapter implementing `JournalEntryReadModel`. Composes the WHERE + HAVING SQL, applies cursor pagination. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ListJournalEntriesResponse.java` | Record `(List<JournalEntryResponse> items, String nextCursor)`. `nextCursor` is null when there's no next page. |

### Modify (production)

| Path | What changes |
|---|---|
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java` | Add static factory `reverse(JournalEntryId originalId, String reason, LocalDate today)` producing a new `JournalEntry` with legs swapped and prefix description. Existing shape unchanged. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java` | Add sealed variant `NotFound(JournalEntryId id) implements JournalError`. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntry.java` | Add `Optional<ReversalMetadata> reverses` and `Optional<ReversedByMetadata> reversedBy` fields via a second canonical constructor; existing `(id, entry)` constructor forwards with `Optional.empty()` for both. |
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java` | No new methods (existing `findById` is enough for Phase A). |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntity.java` | Add `reversesId` (UUID, nullable) + `reversalReason` (String, nullable) columns. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java` | Map new fields both directions. Domain `Optional<ReversalMetadata>` ↔ entity nullable fields. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapter.java` | `save(...)` writes new columns. `findById(...)` populates `reverses`; `reversedBy` stays empty (only the JdbcClient read model populates that via LEFT JOIN). |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java` | Add `GET /journal-entries` (list) + `GET /journal-entries/{id}` (detail). Both `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")` (JOURNAL_READ). |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java` | Add fields: `reversesId`, `reversalReason`, `reversedById`, `reversedAt` (ISO-8601), `reversedBy`, `reversedReason`. All nullable in JSON. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java` | Add `JournalError.NotFound` case → 404 `/problems/journal/not-found`. |
| `docs/openapi/openapi.yaml` | Regenerated via `./mvnw -Popenapi-update verify` after the API changes land. |

### Create (test)

| Path | Responsibility |
|---|---|
| `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReversalTest.java` | Unit tests for `JournalEntry.reverse(...)`. |
| `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQueryTest.java` | Filter record constructors + defaults. |
| `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntryTest.java` | Extend for the new optional fields (or add if absent). |
| `src/test/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryServiceTest.java` | Delegate tests. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModelIT.java` | Testcontainers IT covering all filters, HAVING vs WHERE for amounts, cursor pagination, tenant isolation. |

### Modify (test)

| Path | What changes |
|---|---|
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapterIT.java` | Extend with a `reverses_id` + `reversal_reason` round-trip case. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java` | Extend with list + detail cases (happy path, 404, filters, pagination, auth). |

---

## Tasks

### Task 1: Domain — reverse factory + `JournalError.NotFound` + metadata records

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversalMetadata.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversedByMetadata.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntry.java`
- Test: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReversalTest.java`
- Test: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntryTest.java`

**Interfaces:**
- Consumes: existing `JournalEntry`, `Posting`, `Side`, `JournalEntryId`.
- Produces: `JournalEntry.reverse(JournalEntryId originalId, String reason, LocalDate today) → JournalEntry` (static factory). `ReversalMetadata(JournalEntryId reversesId, String reason)`. `ReversedByMetadata(JournalEntryId reversalId, Instant reversedAt, String reversedBy, String reason)`. `PersistedJournalEntry.reverses() → Optional<ReversalMetadata>`. `PersistedJournalEntry.reversedBy() → Optional<ReversedByMetadata>`. `JournalError.NotFound(JournalEntryId id)`.

- [ ] **Step 1: Create `ReversalMetadata`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;

/** Metadata attached to a persisted reversal entry — the id of what it reverses + the operator-supplied reason. */
public record ReversalMetadata(JournalEntryId reversesId, String reason) {

  public ReversalMetadata {
    Objects.requireNonNull(reversesId, "reversesId");
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }
}
```

- [ ] **Step 2: Create `ReversedByMetadata`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.time.Instant;
import java.util.Objects;

/**
 * Derived read-side view populated by the JdbcClient read model when this entry has been reversed.
 * Never stored on the row itself; populated via LEFT JOIN journal_entries r ON r.reverses_id = e.id.
 */
public record ReversedByMetadata(
    JournalEntryId reversalId, Instant reversedAt, String reversedBy, String reason) {

  public ReversedByMetadata {
    Objects.requireNonNull(reversalId, "reversalId");
    Objects.requireNonNull(reversedAt, "reversedAt");
    Objects.requireNonNull(reversedBy, "reversedBy");
    Objects.requireNonNull(reason, "reason");
  }
}
```

- [ ] **Step 3: Extend `JournalError` with `NotFound`**

Add this record to the `JournalError` sealed interface (keep the file's existing structure):

```java
  record NotFound(JournalEntryId id) implements JournalError {}
```

- [ ] **Step 4: Extend `PersistedJournalEntry`**

Replace the current file with (preserves the existing `(id, entry)` constructor via delegation):

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link JournalEntry} that has been persisted, paired with its storage-assigned id and any
 * reversal-graph metadata the read model has surfaced.
 *
 * <p>{@code reverses} is populated when this entry is itself a reversal (from the row-stored
 * columns). {@code reversedBy} is populated only by the read model via LEFT JOIN — writing the
 * entry does not know whether it will later be reversed, so {@code reversedBy} is always
 * {@code Optional.empty()} at save time.
 */
public record PersistedJournalEntry(
    JournalEntryId id,
    JournalEntry entry,
    Optional<ReversalMetadata> reverses,
    Optional<ReversedByMetadata> reversedBy) {

  public PersistedJournalEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(reverses, "reverses");
    Objects.requireNonNull(reversedBy, "reversedBy");
  }

  /** Backwards-compatible constructor for callers that don't know about the reversal graph. */
  public PersistedJournalEntry(JournalEntryId id, JournalEntry entry) {
    this(id, entry, Optional.empty(), Optional.empty());
  }
}
```

- [ ] **Step 5: Write the failing test for `JournalEntry.reverse`**

Create `JournalEntryReversalTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntry.reverse")
class JournalEntryReversalTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final JournalEntryId ORIGINAL =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 9);

  @Test
  @DisplayName("swaps debit and credit sides on every posting")
  void shouldSwapSidesWhenReversing() {
    JournalEntry original =
        JournalEntry.raw(
            LocalDate.of(2026, 6, 15),
            "original",
            List.of(
                new Posting(new AccountCode("1000"), Side.DEBIT, money(1000), money(1000)),
                new Posting(new AccountCode("3000"), Side.CREDIT, money(1000), money(1000))));

    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "wrong account", TODAY, original);

    assertThat(reversed.postings())
        .extracting(Posting::side)
        .containsExactly(Side.CREDIT, Side.DEBIT);
  }

  @Test
  @DisplayName("uses today's date, not the original's occurred date")
  void shouldUseTodaysDateWhenReversing() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.occurredOn()).isEqualTo(TODAY);
  }

  @Test
  @DisplayName("description is prefixed with 'Reversal of #<id>: <reason>'")
  void shouldPrefixDescriptionWithOriginalIdAndReason() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.description())
        .isEqualTo("Reversal of #" + ORIGINAL.value() + ": typo");
  }

  @Test
  @DisplayName("preserves currency and amounts leg by leg")
  void shouldPreserveAmountsWhenReversing() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.postings().get(0).amount()).isEqualTo(original.postings().get(0).amount());
    assertThat(reversed.postings().get(0).account())
        .isEqualTo(original.postings().get(0).account());
  }

  @Test
  @DisplayName("rejects blank reason")
  void shouldRejectBlankReason() {
    JournalEntry original = anEntry();
    assertThatThrownBy(() -> JournalEntry.reverse(ORIGINAL, "  ", TODAY, original))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JournalEntry anEntry() {
    return JournalEntry.raw(
        LocalDate.of(2026, 6, 15),
        "original",
        List.of(
            new Posting(new AccountCode("1000"), Side.DEBIT, money(1000), money(1000)),
            new Posting(new AccountCode("3000"), Side.CREDIT, money(1000), money(1000))));
  }

  private static Money money(long minor) {
    return new Money(USD, minor);
  }
}
```

**Note:** The test uses `JournalEntry.raw(...)` — a package-private test-only helper. If that doesn't exist, use the codebase's existing test-support factory. If NO such helper exists, use `JournalEntry.of(...)` with a stub `JournalValidationContext` per the ADR-0013 pattern in existing `JournalEntryTest.java` — read that file to copy the shape.

- [ ] **Step 6: Run test to verify FAIL**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B test -Dtest=JournalEntryReversalTest`
Expected: FAIL with `JournalEntry.reverse(...)` unresolved symbol.

- [ ] **Step 7: Implement `JournalEntry.reverse(...)`**

Add to `JournalEntry.java` as a static factory:

```java
  /**
   * Produce a mirror entry that reverses the given original: same accounts, same amounts, opposite
   * sides on every posting. Occurred date is {@code today} (per the corrections spec); description
   * is auto-composed as {@code "Reversal of #<originalId>: <reason>"}.
   *
   * <p>Reversal metadata (the {@code reverses_id} + {@code reversal_reason} columns) is attached at
   * persistence time by the repository adapter, not stored on the {@link JournalEntry} aggregate.
   */
  public static JournalEntry reverse(
      JournalEntryId originalId, String reason, java.time.LocalDate today, JournalEntry original) {
    java.util.Objects.requireNonNull(originalId, "originalId");
    java.util.Objects.requireNonNull(reason, "reason");
    java.util.Objects.requireNonNull(today, "today");
    java.util.Objects.requireNonNull(original, "original");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    java.util.List<Posting> swapped =
        original.postings().stream()
            .map(p -> new Posting(p.account(), p.side().opposite(), p.amount(), p.baseAmount()))
            .toList();
    String description = "Reversal of #" + originalId.value() + ": " + reason;
    return JournalEntry.raw(today, description, swapped);
  }
```

If `Side` doesn't have `.opposite()`, add it (`DEBIT ↔ CREDIT`). If `JournalEntry.raw(...)` doesn't exist as a package-private factory, either add it or use whatever direct constructor exists (verify by reading `JournalEntry.java`).

- [ ] **Step 8: Run test to verify PASS**

Run: `./mvnw -B test -Dtest=JournalEntryReversalTest`
Expected: PASS (5 tests).

- [ ] **Step 9: Extend `PersistedJournalEntryTest`**

Add tests verifying the new fields (create the file if absent):

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PersistedJournalEntry")
class PersistedJournalEntryTest {

  @Test
  @DisplayName("two-arg constructor defaults reversal metadata to empty")
  void shouldDefaultReversalMetadataToEmpty() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    JournalEntry entry = /* build a valid entry via the codebase's test-support factory */;
    PersistedJournalEntry p = new PersistedJournalEntry(id, entry);
    assertThat(p.reverses()).isEqualTo(Optional.empty());
    assertThat(p.reversedBy()).isEqualTo(Optional.empty());
  }
}
```

Replace the `/* build a valid entry */` comment with the actual test-support factory call — read the codebase's existing `JournalEntryTest.java` or `JpaJournalEntryRepositoryIT.java` for the concrete factory.

- [ ] **Step 10: Full unit-test run**

Run: `./mvnw -B test`
Expected: green baseline preserved (existing tests unchanged apart from `PersistedJournalEntry`'s new fields; the `(id, entry)` constructor is preserved).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversalMetadata.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/ReversedByMetadata.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntry.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReversalTest.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/journal/PersistedJournalEntryTest.java
git commit -m "Slice 7 Phase A T1: JournalEntry.reverse + JournalError.NotFound + reversal metadata records"
```

---

### Task 2: Persistence — V10 migration + entity + adapter round-trip

**Files:**
- Create: `src/main/resources/db/migration/V10__journal_entry_reversal.sql`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntity.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapter.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapterIT.java`

**Interfaces:**
- Consumes: `PersistedJournalEntry.reverses() → Optional<ReversalMetadata>` from T1.
- Produces: `JournalEntryRepositoryAdapter.save(entry, TenantId, Optional<ReversalMetadata>)`. On read, `findById` returns a `PersistedJournalEntry` whose `reverses` field is populated from the row (or empty).

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V10__journal_entry_reversal.sql`:

```sql
-- Slice 7 Phase A: journal-entry reversal metadata
--
-- Nullable columns so this migration is additive (rolling-deploy safe).
-- The composite FK preserves multi-tenant isolation: a reversal in tenant A
-- cannot point at an entry in tenant B, even accidentally.

ALTER TABLE journal_entries
  ADD COLUMN reverses_id UUID NULL,
  ADD COLUMN reversal_reason TEXT NULL;

ALTER TABLE journal_entries
  ADD CONSTRAINT journal_entries_reverses_fk
  FOREIGN KEY (tenant_id, reverses_id)
  REFERENCES journal_entries (tenant_id, id);

-- Index the reverse-lookup direction: "was this entry reversed?" and
-- "list this entry's reversal if any." Also covers the T5 findMany LEFT JOIN.
CREATE INDEX journal_entries_reverses_idx
  ON journal_entries (tenant_id, reverses_id);
```

- [ ] **Step 2: Verify migration compiles + runs (empty round-trip IT)**

Run: `./mvnw -B verify -Dit.test=JournalEntryRepositoryAdapterIT -DfailIfNoTests=false`
Expected: PASS (migrations apply cleanly, existing IT green).

- [ ] **Step 3: Write the failing round-trip test in `JournalEntryRepositoryAdapterIT`**

Add this method (adapt to the file's existing helper conventions):

```java
  @Test
  @DisplayName("saves and reads back reverses_id + reversal_reason round-trip")
  void shouldRoundTripReversalMetadata() {
    tenantContext.set(Tenants.DEFAULT_TENANT_ID);
    PersistedJournalEntry original = adapter.save(anEntry("original"));

    JournalEntry reversalEntry =
        JournalEntry.reverse(original.id(), "posted to wrong account", LocalDate.now(), original.entry());
    ReversalMetadata metadata = new ReversalMetadata(original.id(), "posted to wrong account");

    PersistedJournalEntry persistedReversal = adapter.saveReversal(reversalEntry, metadata);

    PersistedJournalEntry loaded =
        adapter.findById(Tenants.DEFAULT_TENANT_ID, persistedReversal.id()).orElseThrow();

    assertThat(loaded.reverses()).isPresent();
    assertThat(loaded.reverses().get().reversesId()).isEqualTo(original.id());
    assertThat(loaded.reverses().get().reason()).isEqualTo("posted to wrong account");
  }
```

- [ ] **Step 4: Run to verify FAIL**

Run: `./mvnw -B verify -Dit.test=JournalEntryRepositoryAdapterIT`
Expected: FAIL — `saveReversal(...)` doesn't exist yet.

- [ ] **Step 5: Extend the entity**

In `JournalEntryEntity.java`, add two fields (adapt to Lombok/JPA style used in the file):

```java
  @Column(name = "reverses_id")
  private UUID reversesId;

  @Column(name = "reversal_reason")
  private String reversalReason;

  // getters/setters or Lombok, matching the file's style
```

- [ ] **Step 6: Extend the entity mapper**

In `JournalEntryEntityMapper.java`, extend the two-way mapping so that:
- `toEntity(PersistedJournalEntry)` writes `reversesId` + `reversalReason` from `reverses().map(ReversalMetadata::reversesId)` / `.map(ReversalMetadata::reason)`.
- `toDomain(JournalEntryEntity)` builds `Optional<ReversalMetadata>` from the two nullable columns.

- [ ] **Step 7: Extend the adapter**

In `JournalEntryRepositoryAdapter.java`, add a `saveReversal` overload (or extend `save`) — the simplest cut is:

```java
  /**
   * Persist an entry as a reversal of an existing entry. Writes the {@code reverses_id} +
   * {@code reversal_reason} columns. Used by ReverseJournalEntryService (Slice 7 Phase B).
   */
  public PersistedJournalEntry saveReversal(JournalEntry entry, ReversalMetadata metadata) {
    // build entity, set reversesId + reversalReason from metadata, save, map back with metadata present
    // (implementation follows the shape of existing save)
  }
```

- [ ] **Step 8: Run round-trip test to verify PASS**

Run: `./mvnw -B verify -Dit.test=JournalEntryRepositoryAdapterIT`
Expected: PASS (existing tests + the new round-trip).

- [ ] **Step 9: Full test suite green**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS (no unit-test regressions).

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/V10__journal_entry_reversal.sql \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntity.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryEntityMapper.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapter.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryRepositoryAdapterIT.java
git commit -m "Slice 7 Phase A T2: V10 migration + entity + adapter reversal round-trip"
```

---

### Task 3: Domain — `JournalEntryQuery` record + `JournalEntryReadModel` port

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQuery.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryPage.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReadModel.java`
- Test: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQueryTest.java`

**Interfaces:**
- Consumes: `TenantId`, `JournalEntryId`, `AccountCode`, `PersistedJournalEntry`.
- Produces: port + record shapes used by T4/T5 (adapter) and T6 (service).

- [ ] **Step 1: Write the failing query record test**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntryQuery")
class JournalEntryQueryTest {

  @Test
  @DisplayName("all filters default to empty and limit is preserved")
  void shouldConstructWithEmptyFilters() {
    JournalEntryQuery q =
        new JournalEntryQuery(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50);
    assertThat(q.limit()).isEqualTo(50);
    assertThat(q.from()).isEqualTo(Optional.empty());
  }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=JournalEntryQueryTest` → FAIL (record doesn't exist).

- [ ] **Step 3: Create `JournalEntryQuery`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Filter + pagination shape for the journal-entry list surface. All filters are optional
 * (Optional.empty means "no filter"); limit is bounded by the caller. Cursor pagination uses
 * {@code after} — the id of the last row on the previous page.
 */
public record JournalEntryQuery(
    Optional<LocalDate> from,
    Optional<LocalDate> to,
    Optional<AccountCode> account,
    Optional<String> q,
    Optional<Long> amountMin,
    Optional<Long> amountMax,
    Optional<JournalEntryId> after,
    int limit) {

  public JournalEntryQuery {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(q, "q");
    Objects.requireNonNull(amountMin, "amountMin");
    Objects.requireNonNull(amountMax, "amountMax");
    Objects.requireNonNull(after, "after");
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200 (got " + limit + ")");
    }
  }
}
```

- [ ] **Step 4: Create `JournalEntryPage`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of query results plus an optional cursor to the next page. */
public record JournalEntryPage(
    List<PersistedJournalEntry> items, Optional<JournalEntryId> nextCursor) {

  public JournalEntryPage {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(nextCursor, "nextCursor");
    items = List.copyOf(items);
  }
}
```

- [ ] **Step 5: Create `JournalEntryReadModel`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/**
 * Read-side port for journal-entry browsing. Implemented in infrastructure with a JdbcClient
 * adapter, per the {@code TrialBalanceReadModel} precedent from Slice 4.
 */
public interface JournalEntryReadModel {

  JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query);

  Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id);
}
```

- [ ] **Step 6: Run to verify test PASS**

Run: `./mvnw -B test -Dtest=JournalEntryQueryTest` → PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQuery.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryPage.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryReadModel.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryQueryTest.java
git commit -m "Slice 7 Phase A T3: JournalEntryQuery + JournalEntryPage + JournalEntryReadModel port"
```

---

### Task 4: Infrastructure — `JournalEntryJdbcReadModel.findMany` with filters + cursor

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModel.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModelIT.java`

**Interfaces:**
- Consumes: `JournalEntryReadModel` port from T3; existing `JdbcClient` bean.
- Produces: implementation of `findMany(TenantId, JournalEntryQuery) → JournalEntryPage`. `findById` stub returning `Optional.empty()` (T5 implements it).

- [ ] **Step 1: Write the failing IT — one basic list case**

Create `JournalEntryJdbcReadModelIT.java` with the header + one test to bootstrap. Follow the shape of `TrialBalanceJdbcReadModelIT.java` — read that file for the `@SpringBootTest(webEnvironment=NONE)` + `@Container @ServiceConnection` + `@Import(TestSecurityConfig.class)` pattern.

```java
@Test
@DisplayName("findMany with no filters returns all entries ordered by id ASC, respecting limit")
void shouldReturnAllEntriesOrderedById() {
  // seed 3 entries via existing repository adapter or SQL insert
  seedEntry("first",  LocalDate.of(2026, 6, 1));
  seedEntry("second", LocalDate.of(2026, 6, 2));
  seedEntry("third",  LocalDate.of(2026, 6, 3));

  JournalEntryQuery all = query(50);
  JournalEntryPage page = readModel.findMany(Tenants.DEFAULT_TENANT_ID, all);

  assertThat(page.items()).hasSize(3);
  assertThat(page.nextCursor()).isEqualTo(Optional.empty());
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B verify -Dit.test=JournalEntryJdbcReadModelIT` → FAIL (bean doesn't exist).

- [ ] **Step 3: Implement `JournalEntryJdbcReadModel`**

Sketch — implementer expands the SQL:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.journal.*;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * JdbcClient-backed read model for journal-entry browsing.
 *
 * <p>Filter composition: WHERE clauses AND together on the raw journal_entries columns; amount
 * filters live in HAVING because SUM(p.debit_minor) is aggregated. Cursor pagination uses
 * {@code id > cursor} — UUID v7 IDs sort by time, so ascending id order is also chronological.
 *
 * <p>Requests {@code limit + 1} rows; if the extra row came back, drops it and reports the
 * next cursor as the id of the last kept row.
 */
@Component
public class JournalEntryJdbcReadModel implements JournalEntryReadModel {

  private final JdbcClient jdbc;

  public JournalEntryJdbcReadModel(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query) {
    String sql = """
        SELECT e.id, e.occurred_on, e.description, e.posted_at,
               e.reverses_id, e.reversal_reason
        FROM journal_entries e
        WHERE e.tenant_id = :tenant
          AND (:from IS NULL OR e.occurred_on >= :from)
          AND (:to   IS NULL OR e.occurred_on <= :to)
          AND (:acct IS NULL OR EXISTS (
                  SELECT 1 FROM postings p
                  WHERE p.journal_entry_id = e.id
                    AND p.account_code = :acct))
          AND (:q    IS NULL OR e.description ILIKE '%' || :q || '%')
          AND (:after IS NULL OR e.id > :after)
          AND (:min IS NULL OR (
                  SELECT SUM(p.debit_minor) FROM postings p
                  WHERE p.journal_entry_id = e.id) >= :min)
          AND (:max IS NULL OR (
                  SELECT SUM(p.debit_minor) FROM postings p
                  WHERE p.journal_entry_id = e.id) <= :max)
        ORDER BY e.id ASC
        LIMIT :limit
        """;

    List<PersistedJournalEntry> rows = jdbc.sql(sql)
        .param("tenant", tenantId.value())
        .param("from",  query.from().orElse(null))
        .param("to",    query.to().orElse(null))
        .param("acct",  query.account().map(a -> a.value()).orElse(null))
        .param("q",     query.q().orElse(null))
        .param("after", query.after().map(id -> id.value()).orElse(null))
        .param("min",   query.amountMin().orElse(null))
        .param("max",   query.amountMax().orElse(null))
        .param("limit", query.limit() + 1)
        .query((rs, i) -> mapRowWithPostings(rs, tenantId))
        .list();

    Optional<JournalEntryId> next = Optional.empty();
    if (rows.size() > query.limit()) {
      rows = rows.subList(0, query.limit());
      next = Optional.of(rows.get(rows.size() - 1).id());
    }
    return new JournalEntryPage(rows, next);
  }

  @Override
  public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
    return Optional.empty(); // T5 implements
  }

  private PersistedJournalEntry mapRowWithPostings(java.sql.ResultSet rs, TenantId tenantId) {
    // For Phase A, defer to a separate query for postings per row. Optimization
    // (single-round-trip via aggregate) is a follow-up. See Task 4 step 4 note.
    throw new UnsupportedOperationException("expand in step 4");
  }
}
```

The row → domain mapping deserves its own step because it involves a second query per row for postings. See step 4.

- [ ] **Step 4: Expand the row-mapping helper**

Postings must ride along on each entry (the caller needs them to compute total debit / credit and to render). Simplest correct implementation for Phase A: after the primary query returns the entry rows, run one bulk-select for postings keyed by the returned ids. Sketch:

```java
private List<PersistedJournalEntry> hydrate(
    TenantId tenantId, List<Map<String, Object>> entryRows) {
  if (entryRows.isEmpty()) return List.of();

  List<UUID> ids = entryRows.stream().map(r -> (UUID) r.get("id")).toList();

  Map<UUID, List<Posting>> postingsById = jdbc.sql("""
      SELECT journal_entry_id, account_code, side, amount_minor, amount_ccy,
             base_amount_minor, base_amount_ccy
      FROM postings
      WHERE tenant_id = :tenant AND journal_entry_id = ANY(:ids)
      """)
      .param("tenant", tenantId.value())
      .param("ids", ids.toArray(new UUID[0]))
      .query((rs, i) -> new Object[] { rs.getObject("journal_entry_id", UUID.class), buildPosting(rs) })
      .list()
      .stream()
      .collect(Collectors.groupingBy(a -> (UUID) a[0], Collectors.mapping(a -> (Posting) a[1], Collectors.toList())));

  return entryRows.stream()
      .map(r -> toPersisted(r, postingsById.getOrDefault(r.get("id"), List.of())))
      .toList();
}
```

Implementer fills in `buildPosting` and `toPersisted` following the existing entity-mapper shape. Refactor the primary `findMany` to collect the raw rows via `.query((rs, i) -> mapEntryRow(rs)).list()` returning `Map<String, Object>` rows, then call `hydrate`.

Read `TrialBalanceJdbcReadModel.java` for the JdbcClient shape conventions used elsewhere.

- [ ] **Step 5: Run the bootstrap test to verify PASS**

Run: `./mvnw -B verify -Dit.test=JournalEntryJdbcReadModelIT` → PASS (the one test from step 1).

- [ ] **Step 6: Add filter + pagination tests**

Extend `JournalEntryJdbcReadModelIT` with these cases (each a `@Test`):

1. `shouldFilterByFromAndToDates` — seed 3 entries across June/July, query `from=2026-06-01, to=2026-06-30`, assert only June entries returned.
2. `shouldFilterByAccountCode` — seed entries touching 1000 and 3000; query `account=1000`, assert only entries with a 1000-touching posting.
3. `shouldFilterByDescriptionSubstring` — seed "coffee purchase", "office supplies"; query `q=coffee`, assert only the first.
4. `shouldFilterByAmountRangeUsingHavingNotWhere` — seed entry A (single posting 10 USD debit + 10 USD credit ↔ total debit 10) and entry B (single posting 100 USD debit + 100 USD credit ↔ total debit 100). Query `amountMin=50`. Assert only B returned. Then add a THIRD entry C with TWO postings summing to 50 debit total but individual postings of 25 each; query `amountMin=50` again → C IS returned (proves HAVING SUM, not WHERE per-posting).
5. `shouldPaginateWithCursor` — seed 5 entries. Query `limit=2` → 2 items + non-empty cursor. Query with `after=<cursor>` → next 2 items + cursor. Query with `after=<cursor2>` → 1 item + empty cursor.
6. `shouldNotReturnEntriesFromOtherTenants` — seed entry in `Tenants.DEFAULT_TENANT_ID` and one in a manually inserted second tenant id; query as DEFAULT → only default's entry.

- [ ] **Step 7: Run all IT cases**

Run: `./mvnw -B verify -Dit.test=JournalEntryJdbcReadModelIT` → PASS (7 total).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModel.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModelIT.java
git commit -m "Slice 7 Phase A T4: JournalEntryJdbcReadModel.findMany with filters + cursor + IT"
```

---

### Task 5: `JournalEntryJdbcReadModel.findById` with LEFT JOIN `reversedBy` metadata

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModel.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModelIT.java`

**Interfaces:**
- Consumes: `PersistedJournalEntry.reversedBy` field from T1.
- Produces: `findById` returning `Optional<PersistedJournalEntry>` with both `reverses` (from the row) and `reversedBy` (LEFT JOIN) populated.

- [ ] **Step 1: Write the failing IT**

Add three cases to `JournalEntryJdbcReadModelIT`:

1. `shouldReturnEmptyWhenEntryNotFound` — query random id, assert `Optional.empty()`.
2. `shouldReturnEntryWithoutReversalMetadataWhenNeverReversed` — seed a normal entry, `findById` → present, `reverses().isEmpty()` and `reversedBy().isEmpty()`.
3. `shouldReturnReversedByMetadataForOriginalOfAReversalPair` — seed entry A, seed entry B saved via `saveReversal(B, ReversalMetadata(A.id, "typo"))`, call `findById(A)` → `reversedBy()` present with A's reversal id = B.id.

Also: `shouldReturnReversesMetadataForReversalEntry` — same setup, call `findById(B)` → `reverses()` present with `reversesId = A.id`.

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B verify -Dit.test=JournalEntryJdbcReadModelIT` → FAIL on the 4 new tests.

- [ ] **Step 3: Implement `findById`**

Replace the stub with a real query. The LEFT JOIN is on `r.reverses_id = e.id`.

```java
@Override
public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
  String sql = """
      SELECT
        e.id, e.occurred_on, e.description, e.posted_at,
        e.reverses_id, e.reversal_reason,
        r.id           AS reversed_by_id,
        r.posted_at    AS reversed_at,
        r.posted_by    AS reversed_by,
        r.reversal_reason AS reversed_reason
      FROM journal_entries e
      LEFT JOIN journal_entries r
        ON r.tenant_id = e.tenant_id
       AND r.reverses_id = e.id
      WHERE e.tenant_id = :tenant AND e.id = :id
      """;

  List<Map<String, Object>> rows = jdbc.sql(sql)
      .param("tenant", tenantId.value())
      .param("id", id.value())
      .query()
      .listOfRows();

  if (rows.isEmpty()) return Optional.empty();

  List<PersistedJournalEntry> hydrated = hydrate(tenantId, rows);
  // hydrate builds reverses metadata; also read reversed_by_id / at / by from the row here.
  return Optional.of(withReversedBy(hydrated.get(0), rows.get(0)));
}

private PersistedJournalEntry withReversedBy(PersistedJournalEntry base, Map<String, Object> row) {
  UUID reversalId = (UUID) row.get("reversed_by_id");
  if (reversalId == null) return base;
  return new PersistedJournalEntry(
      base.id(), base.entry(), base.reverses(),
      Optional.of(new ReversedByMetadata(
          new JournalEntryId(reversalId),
          ((java.sql.Timestamp) row.get("reversed_at")).toInstant(),
          (String) row.get("reversed_by"),
          (String) row.get("reversed_reason"))));
}
```

Assumes `journal_entries` has a `posted_at` (`Instant`) and `posted_by` (`String`) column. If they don't (they don't in V1 — verify by reading `V1__journal_entries.sql`), fall back to the entry's own metadata columns. If the schema truly lacks these, the plan needs an amendment — flag it as BLOCKED and check with the controller.

- [ ] **Step 4: Run IT to verify PASS**

Run: `./mvnw -B verify -Dit.test=JournalEntryJdbcReadModelIT` → PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModel.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/journal/JournalEntryJdbcReadModelIT.java
git commit -m "Slice 7 Phase A T5: JournalEntryJdbcReadModel.findById with reversedBy LEFT JOIN"
```

---

### Task 6: Application — `JournalEntryQueryService`

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryService.java`
- Test: `src/test/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryServiceTest.java`

**Interfaces:**
- Consumes: `JournalEntryReadModel` port from T3.
- Produces: `JournalEntryQueryService.findMany(TenantId, JournalEntryQuery) → JournalEntryPage` and `.findById(TenantId, JournalEntryId) → Optional<PersistedJournalEntry>`.

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.application.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.*;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("JournalEntryQueryService")
class JournalEntryQueryServiceTest {

  private final JournalEntryReadModel readModel = Mockito.mock(JournalEntryReadModel.class);
  private final JournalEntryQueryService service = new JournalEntryQueryService(readModel);

  @Test
  @DisplayName("findMany delegates to read model with the same tenant + query")
  void shouldDelegateFindMany() {
    JournalEntryQuery query = /* build a query with limit=50, empty filters */;
    JournalEntryPage expected = new JournalEntryPage(List.of(), Optional.empty());
    Mockito.when(readModel.findMany(Mockito.any(), Mockito.eq(query))).thenReturn(expected);

    JournalEntryPage got = service.findMany(Tenants.DEFAULT_TENANT_ID, query);

    assertThat(got).isSameAs(expected);
  }

  @Test
  @DisplayName("findById delegates to read model")
  void shouldDelegateFindById() {
    JournalEntryId id = /* build a JournalEntryId */;
    Optional<PersistedJournalEntry> expected = Optional.empty();
    Mockito.when(readModel.findById(Mockito.any(), Mockito.eq(id))).thenReturn(expected);

    assertThat(service.findById(Tenants.DEFAULT_TENANT_ID, id)).isSameAs(expected);
  }
}
```

Fill in the query and id constructions.

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=JournalEntryQueryServiceTest` → FAIL.

- [ ] **Step 3: Implement the service**

```java
package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.*;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/**
 * Thin read-side application service. All logic lives in the read model — this exists purely to
 * keep the controller from reaching directly into the port (hexagonal layering + ArchUnit rule
 * WEB_DOES_NOT_DEPEND_ON_PERSISTENCE_ENTITIES).
 */
public class JournalEntryQueryService {

  private final JournalEntryReadModel readModel;

  public JournalEntryQueryService(JournalEntryReadModel readModel) {
    this.readModel = readModel;
  }

  public JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query) {
    return readModel.findMany(tenantId, query);
  }

  public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
    return readModel.findById(tenantId, id);
  }
}
```

- [ ] **Step 4: Wire as a Spring bean**

Add a `@Bean` method in `ApplicationConfig.java` (same pattern as existing services):

```java
  @Bean
  public JournalEntryQueryService journalEntryQueryService(JournalEntryReadModel readModel) {
    return new JournalEntryQueryService(readModel);
  }
```

- [ ] **Step 5: Run test to PASS**

Run: `./mvnw -B test -Dtest=JournalEntryQueryServiceTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryService.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java \
        src/test/java/co/embracejoy/accounting/keystone/application/journal/JournalEntryQueryServiceTest.java
git commit -m "Slice 7 Phase A T6: JournalEntryQueryService"
```

---

### Task 7: DTOs — extended `JournalEntryResponse` + `ListJournalEntriesResponse`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ListJournalEntriesResponse.java`

**Interfaces:**
- Consumes: `PersistedJournalEntry.reverses()` + `.reversedBy()` from T1.
- Produces: extended `JournalEntryResponse` shape with `reversesId`, `reversalReason`, `reversedById`, `reversedAt`, `reversedBy`, `reversedReason` (all nullable in JSON). New `ListJournalEntriesResponse(List<JournalEntryResponse>, String nextCursor)`.

- [ ] **Step 1: Extend `JournalEntryResponse`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.ReversedByMetadata;
import java.time.LocalDate;
import java.util.List;

public record JournalEntryResponse(
    String id,
    LocalDate occurredOn,
    String description,
    List<PostingResponse> postings,
    String reversesId,
    String reversalReason,
    String reversedById,
    String reversedAt,
    String reversedBy,
    String reversedReason) {

  public static JournalEntryResponse of(PersistedJournalEntry p) {
    return new JournalEntryResponse(
        p.id().value().toString(),
        p.entry().occurredOn(),
        p.entry().description(),
        p.entry().postings().stream().map(PostingResponse::of).toList(),
        p.reverses().map(ReversalMetadata::reversesId).map(id -> id.value().toString()).orElse(null),
        p.reverses().map(ReversalMetadata::reason).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reversalId).map(id -> id.value().toString()).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reversedAt).map(Object::toString).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reversedBy).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reason).orElse(null));
  }
}
```

- [ ] **Step 2: Create `ListJournalEntriesResponse`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import java.util.List;

public record ListJournalEntriesResponse(List<JournalEntryResponse> items, String nextCursor) {

  public static ListJournalEntriesResponse of(JournalEntryPage page) {
    return new ListJournalEntriesResponse(
        page.items().stream().map(JournalEntryResponse::of).toList(),
        page.nextCursor().map(id -> id.value().toString()).orElse(null));
  }
}
```

- [ ] **Step 3: Verify baseline is still green**

Run: `./mvnw -B test`
Expected: PASS (existing tests may break if they construct `JournalEntryResponse` with the old 4-arg shape; update them to use the static `of(...)` factory or new full constructor).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/JournalEntryResponse.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ListJournalEntriesResponse.java
git commit -m "Slice 7 Phase A T7: extend JournalEntryResponse + add ListJournalEntriesResponse"
```

---

### Task 8: Controller — `GET /journal-entries/{id}` + `ResultMapper` `NotFound` case

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

**Interfaces:**
- Consumes: `JournalEntryQueryService.findById` from T6, extended `JournalEntryResponse` from T7, `JournalError.NotFound` from T1.
- Produces: `GET /journal-entries/{id}` returning 200 + `JournalEntryResponse`, or 404 `/problems/journal/not-found`.

- [ ] **Step 1: Write the failing test cases**

Add to `JournalEntryControllerTest.java` (follow the existing `@WebMvcTest` shape used for POST tests):

```java
  @Test
  @DisplayName("GET /journal-entries/{id} returns 200 with entry")
  void shouldReturn200WhenEntryFound() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-0000000000ee"));
    PersistedJournalEntry persisted = /* build via existing test-support factory */;
    Mockito.when(queryService.findById(Tenants.DEFAULT_TENANT_ID, id)).thenReturn(Optional.of(persisted));

    mvc.perform(get("/journal-entries/" + id.value())
            .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.value().toString()));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 404 when entry not found")
  void shouldReturn404WhenEntryNotFound() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    Mockito.when(queryService.findById(Mockito.any(), Mockito.eq(id))).thenReturn(Optional.empty());

    mvc.perform(get("/journal-entries/" + id.value()).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/not-found")));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 401 when no auth")
  void shouldReturn401WhenNoAuth() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    mvc.perform(get("/journal-entries/" + id.value())).andExpect(status().isUnauthorized());
  }
```

Adapt `withTestAuth`, `queryService`, and the entry-building factory to the file's existing conventions — read the file first.

Also add `@MockitoBean JournalEntryQueryService queryService` to the test class.

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest` → FAIL (new tests missing GET handler).

- [ ] **Step 3: Add the handler**

In `JournalEntryController.java`:

```java
  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "Fetch one journal entry",
      description =
          "Returns the entry with the given UUID, including reversal metadata (if this entry"
              + " reverses another OR has been reversed by another). 404 if not found.")
  public ResponseEntity<?> get(@PathVariable String id) {
    JournalEntryId jid;
    try {
      jid = new JournalEntryId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return error(new JournalError.NotFound(new JournalEntryId(UUID.nameUUIDFromBytes(id.getBytes()))));
    }
    return queryService.findById(tenantContext.require(), jid)
        .<ResponseEntity<?>>map(p -> ResponseEntity.ok(JournalEntryResponse.of(p)))
        .orElseGet(() -> error(new JournalError.NotFound(jid)));
  }
```

`error(...)` helper: if the controller doesn't already have one, add it — the shape of `TenantController.error(TenantError)` from Slice 5 D-admin-api is the reference.

- [ ] **Step 4: Add `NotFound` mapping in `ResultMapper`**

Extend `toProblemDetail(JournalError)`:

```java
      case JournalError.NotFound n ->
          problem(HttpStatus.NOT_FOUND, "/journal/not-found",
              "Journal entry not found",
              "No journal entry with id '" + n.id().value() + "'.");
```

- [ ] **Step 5: Run to verify PASS**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java
git commit -m "Slice 7 Phase A T8: GET /journal-entries/{id} + JournalError.NotFound mapping"
```

---

### Task 9: Controller — `GET /journal-entries` (list)

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

**Interfaces:**
- Consumes: `JournalEntryQueryService.findMany` from T6, `ListJournalEntriesResponse.of(JournalEntryPage)` from T7, `JournalEntryQuery` record from T3.
- Produces: `GET /journal-entries?from=&to=&account=&q=&amountMin=&amountMax=&limit=&after=` returning 200 + `ListJournalEntriesResponse`.

- [ ] **Step 1: Write the failing tests**

Add to `JournalEntryControllerTest.java`:

```java
  @Test
  @DisplayName("GET /journal-entries returns items + null nextCursor when under limit")
  void shouldReturn200WithItemsAndNoCursor() throws Exception {
    JournalEntryPage page = new JournalEntryPage(
        List.of(anEntry("01902f9f-0000-7000-8000-000000000001", "one")),
        Optional.empty());
    Mockito.when(queryService.findMany(Mockito.eq(Tenants.DEFAULT_TENANT_ID), Mockito.any()))
        .thenReturn(page);

    mvc.perform(get("/journal-entries").with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  @DisplayName("GET /journal-entries with filters passes them into the service")
  void shouldPassFiltersThroughToService() throws Exception {
    Mockito.when(queryService.findMany(Mockito.any(), Mockito.any()))
        .thenReturn(new JournalEntryPage(List.of(), Optional.empty()));

    mvc.perform(get("/journal-entries")
            .param("from", "2026-06-01")
            .param("to", "2026-06-30")
            .param("account", "1000")
            .param("q", "coffee")
            .param("amountMin", "100")
            .param("amountMax", "1000")
            .param("limit", "20")
            .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isOk());

    ArgumentCaptor<JournalEntryQuery> captor = ArgumentCaptor.forClass(JournalEntryQuery.class);
    Mockito.verify(queryService).findMany(Mockito.eq(Tenants.DEFAULT_TENANT_ID), captor.capture());
    JournalEntryQuery q = captor.getValue();
    assertThat(q.from()).isEqualTo(Optional.of(LocalDate.of(2026, 6, 1)));
    assertThat(q.q()).isEqualTo(Optional.of("coffee"));
    assertThat(q.amountMin()).isEqualTo(Optional.of(100L));
    assertThat(q.limit()).isEqualTo(20);
  }

  @Test
  @DisplayName("GET /journal-entries returns nextCursor when service supplies one")
  void shouldReturnNextCursorWhenPresent() throws Exception {
    JournalEntryId nextId = new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000099"));
    Mockito.when(queryService.findMany(Mockito.any(), Mockito.any()))
        .thenReturn(new JournalEntryPage(List.of(), Optional.of(nextId)));

    mvc.perform(get("/journal-entries").with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(nextId.value().toString()));
  }

  @Test
  @DisplayName("GET /journal-entries returns 400 when limit exceeds max")
  void shouldReturn400WhenLimitExceedsMax() throws Exception {
    mvc.perform(get("/journal-entries").param("limit", "999").with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isBadRequest());
  }
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest` → FAIL (handler missing).

- [ ] **Step 3: Implement the list handler**

Add to `JournalEntryController.java`:

```java
  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "List journal entries",
      description =
          "Cursor-paginated list of entries in the current tenant. Supports filtering by date"
              + " range (from/to), account (any posting touches this account code), description"
              + " substring (q, ILIKE), and total-debit amount range (amountMin/amountMax).")
  public ListJournalEntriesResponse list(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String account,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long amountMin,
      @RequestParam(required = false) Long amountMax,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
    JournalEntryQuery query = new JournalEntryQuery(
        Optional.ofNullable(from),
        Optional.ofNullable(to),
        Optional.ofNullable(account).map(AccountCode::new),
        Optional.ofNullable(q),
        Optional.ofNullable(amountMin),
        Optional.ofNullable(amountMax),
        Optional.ofNullable(after).map(s -> new JournalEntryId(UUID.fromString(s))),
        limit);
    return ListJournalEntriesResponse.of(queryService.findMany(tenantContext.require(), query));
  }
```

For the 400-on-invalid-limit case: `IllegalArgumentException` is mapped by the existing `ValidationExceptionHandler`. Verify by running the test.

- [ ] **Step 4: Run to verify PASS**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java
git commit -m "Slice 7 Phase A T9: GET /journal-entries (list with filters + cursor)"
```

---

### Task 10: OpenAPI regen + full verify + PR

**Files:**
- Modify: `docs/openapi/openapi.yaml`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: pushed branch + PR ready for review.

- [ ] **Step 1: Regenerate the OpenAPI snapshot**

Run: `docker compose up -d postgres && JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B -Popenapi-update verify`
Expected: BUILD SUCCESS; `docs/openapi/openapi.yaml` updated with the two new endpoints + extended `JournalEntryResponse` + `ListJournalEntriesResponse` + `/problems/journal/not-found` schema.

- [ ] **Step 2: Full CI-parity gate**

Run: `./mvnw -B clean verify -Popenapi-gate`
Expected: BUILD SUCCESS. openapi-diff should report "backward compatible" (two new GET endpoints, extended response schema).

- [ ] **Step 3: Commit the snapshot regen**

```bash
git add docs/openapi/openapi.yaml
git commit -m "Slice 7 Phase A T10: regenerate OpenAPI snapshot for /journal-entries GET"
```

- [ ] **Step 4: Push + open PR**

```bash
git push -u origin slice-7-phase-a-list-detail
gh pr create --title "Slice 7 Phase A: journal-entry list + detail API" --body "$(cat <<'EOF'
## Summary

Ships Phase A of Slice 7 per the [merged design spec](docs/superpowers/specs/2026-07-09-slice-7-journal-entry-corrections-design.md):

- **Migration V10** — nullable \`reverses_id\` + \`reversal_reason\` columns on \`journal_entries\`, composite FK preserving tenant isolation, index on \`(tenant_id, reverses_id)\`.
- **Domain** — \`JournalEntry.reverse(...)\` factory (used by Phase B), \`ReversalMetadata\` + \`ReversedByMetadata\` records, extended \`PersistedJournalEntry\`, \`JournalError.NotFound\`.
- **Persistence** — extended entity + mapper + adapter for the reverses fields; new \`JournalEntryJdbcReadModel\` (JdbcClient, per Slice 4 precedent) with filter + cursor list and LEFT-JOIN detail.
- **Application** — thin \`JournalEntryQueryService\` delegating to the read model.
- **API** — \`GET /journal-entries\` (list with filters: from/to, account, q, amountMin, amountMax, cursor pagination) and \`GET /journal-entries/{id}\` (detail with reversal metadata). Both gated on \`JOURNAL_READ\` (ADMIN, BOOKKEEPER, READ_ONLY).
- **OpenAPI snapshot** regenerated.

Phase B (reverse endpoint) and Phase C (UI) are separate PRs.

## Test plan

- [x] \`./mvnw -B clean verify -Popenapi-gate\` — full CI-parity green.
- [ ] Manual: \`docker compose up\`, mint a JWT via \`JwtTestSupport\` (or use the smoke IT auth), POST a couple entries via the existing endpoint, then \`GET /journal-entries\` and \`GET /journal-entries/<id>\`.

Refs Slice 7.
EOF
)"
```

- [ ] **Step 5: Await CI**

If CI fails on an OpenAPI diff or coverage gate, diagnose via `gh run view --log-failed`.

---

## Self-Review

**1. Spec coverage:**
- §1 Architecture — T1 (domain), T2 (persistence), T3 (port + query record), T4/T5 (adapter), T6 (service), T8/T9 (controller). ✓
- §2 File layout — every file listed appears in a task. ✓
- §3 Data flow §3.2 (list), §3.3 (get one), §3.5 (NotFound) — covered by T4, T5, T8, T9. ✓
- §4 Error handling — `NotFound` in T8; existing paths (validation, closed-period) untouched. ✓
- §5 Testing — 5.1 domain (T1), 5.3 persistence IT (T2/T4/T5), 5.4 API @WebMvcTest (T8/T9). 5.2 application unit (T6). ✓
- §6.4 migration additive — T2 verifies. ✓
- Phase B reverse and Phase C UI are OUT of scope, correctly.

**2. Placeholder scan:**
- T5 step 3 has a `// hydrate builds reverses metadata; also read reversed_by_id / at / by from the row here.` comment that's borderline — the actual `withReversedBy` helper is shown just below and does the work. Kept.
- Test bodies in T6 have `/* build a query */` and `/* build a JournalEntryId */` — instructing the implementer to use existing conventions rather than embedding fabricated construction that may not match the codebase. Called out explicitly.
- T2 step 6 says "adapt to Lombok/JPA style used in the file" — pointer, not placeholder.

**3. Type consistency:**
- `JournalEntry.reverse(JournalEntryId originalId, String reason, LocalDate today, JournalEntry original)` — 4 args. Consumed by no other task in Phase A (Phase B service uses it).
- `PersistedJournalEntry` — old `(id, entry)` constructor preserved; new fields `reverses` (Optional<ReversalMetadata>) and `reversedBy` (Optional<ReversedByMetadata>). Consistent across T1, T2, T5, T7.
- `JournalEntryQuery` field order matches T3 record → T7 static factory → T9 controller construction. ✓
- `JournalEntryJdbcReadModel.findMany` returns `JournalEntryPage` (T3 shape); T6 service preserves; T9 controller maps via `ListJournalEntriesResponse.of` (T7). ✓
- `JournalError.NotFound(JournalEntryId id)` — same shape in T1 (domain), T8 (mapper case), T8 (controller error helper). ✓

Ready for handoff.
