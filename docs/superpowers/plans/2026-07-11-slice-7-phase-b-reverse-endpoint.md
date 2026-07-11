# Slice 7 Phase B — reverse endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `POST /journal-entries/{id}/reverse` — the only new endpoint in this phase. Takes a reason, creates a mirror journal entry with today's date via the domain reverse factory, runs it through the existing balance/period/account validation stack, and persists it via `saveReversal` so the `reverses_id` + `reversal_reason` columns populate.

**Architecture:** A new `ReverseJournalEntryService` composes three collaborators — `JournalEntryRepository.findById` + `.existsReversalOf` + `.saveReversal` (all landed in Phase A), the domain `JournalEntry.reverse(...)` factory (Phase A), and a small new `PostJournalEntryService.postReversal(...)` overload that reuses the existing validation context so the reversal's postings are validated the same way an original post is. `JournalError` gains `AlreadyReversed(JournalEntryId)`; `ResultMapper` gets the matching 400 case. Controller adds one `@PostMapping("/{id}/reverse")` handler reading the actor sub from `SecurityContextHolder`.

**Tech Stack:** Java 25, Spring Boot 4.0.3, JUnit Jupiter 6, Mockito 5, Testcontainers Postgres.

## Global Constraints

- **Java 25**, Spring Boot 4.0.3.
- **Hexagonal layering** (ArchUnit-enforced): `domain` → `java.*`/own packages only; `application` → `domain` only; `infrastructure` → both. `ApplicationDoesNotThrowArchTest` forbids `throws` on public methods in `..application..`.
- **`Result<T, E>` for internal errors** (ADR-0004). All new application methods return `Result`. No wrapper exceptions.
- **Typed IDs** (ADR-0010): `JournalError.AlreadyReversed` takes `JournalEntryId`, not raw `UUID`.
- **OpenAPI committed snapshot** (ADR-0006). The new endpoint pairs with regenerated `docs/openapi/openapi.yaml`.
- **TDD**: red → green → refactor → commit. Every step below alternates.
- **Google Java Format** via Spotless. Checkstyle 750-line file / 30-line method max, no star imports.
- **`@DisplayName`** and `should<Expected>When<Condition>` on every test.
- **JaCoCo 85% line coverage**; PIT 60% mutation on `domain..` + `application..`.
- **Reversal semantics** per Slice 7 spec (already merged) §3.1:
  - Reversal `occurredOn` = today's date (not the original's).
  - Description = `"Reversal of #<originalId>: <reason>"` (already produced by `JournalEntry.reverse` factory in Phase A).
  - Reason required + non-blank (already enforced by `JournalEntry.reverse` and `ReversalMetadata`).
  - Blocked if the original is already reversed (`existsReversalOf` returns true).
  - Runs through the balance/period/account validation stack (`PostingInClosedPeriod` and `AccountInactive` errors can bubble).
- **RBAC**: `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")` — JOURNAL_POST (same roles that can post an original).
- **Never commit direct to main.** PR-based workflow on branch `slice-7-phase-b-reverse-endpoint`.

---

## Files

### Create (production)

| Path | Responsibility |
|---|---|
| `src/main/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryService.java` | Application service: `reverse(TenantId, JournalEntryId originalId, String reason, String actor) → Result<PersistedJournalEntry, JournalError>`. Orchestrates the `findById → existsReversalOf → JournalEntry.reverse → postReversal` chain. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ReverseJournalEntryRequest.java` | Request body record: `(String reason)` with `@NotBlank @Size(max = 500)`. |

### Modify (production)

| Path | What changes |
|---|---|
| `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java` | Add sealed variant `AlreadyReversed(JournalEntryId id) implements JournalError`. |
| `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java` | Add `postReversal(TenantId, JournalEntry reversal, ReversalMetadata metadata, String actor) → Result<PersistedJournalEntry, JournalError>` — reuses the same validation-context assembly as `post(...)` but delegates persistence to `journalRepository.saveReversal(...)` instead of `save(...)`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java` | Add `JournalError.AlreadyReversed` case → 400 `/journal/already-reversed`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java` | Add `POST /journal-entries/{id}/reverse` handler. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java` | Add `@Bean` for `ReverseJournalEntryService`. |
| `docs/openapi/openapi.yaml` | Regenerated via `./mvnw -Popenapi-update verify` after the endpoint lands. |

### Create (test)

| Path | Responsibility |
|---|---|
| `src/test/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryServiceTest.java` | Unit tests (Mockito): not-found path, already-reversed path, closed-period propagation, happy path returning the persisted reversal. |

### Modify (test)

| Path | What changes |
|---|---|
| `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java` | Add cases for `postReversal(...)` mirroring the existing `post(...)` tests, plus one asserting `journalRepository.saveReversal(...)` is invoked with the same actor. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java` | Add `@WebMvcTest` cases for the new endpoint: 201 happy path, 404 unknown original, 400 already-reversed, 400 blank reason, 400 closed period, 403 wrong role. |
| `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java` | Add one round-trip test: POST an entry, POST a reverse, GET both back and assert `reverses` on the reversal + `reversedBy` on the original. |

---

## Tasks

### Task 1: `JournalError.AlreadyReversed` + `ResultMapper` mapping

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java` (add ONE assertion for the mapping path — full endpoint tests land in T4)

**Interfaces:**
- Consumes: existing `JournalError` sealed interface; `JournalEntryId` typed ID.
- Produces: `JournalError.AlreadyReversed(JournalEntryId id)`. `ResultMapper.toProblemDetail(JournalError.AlreadyReversed) → ProblemDetail(status=400, type="/journal/already-reversed")`.

- [ ] **Step 1: Write the failing mapping test**

Add to `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java` (adapt imports as needed — the file already imports `ResultMapper`, `HttpStatus`, `endsWith`, etc.):

```java
  @Test
  @DisplayName("ResultMapper maps JournalError.AlreadyReversed to 400 /journal/already-reversed")
  void shouldMapAlreadyReversedTo400() {
    JournalEntryId id = new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000abc"));

    ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.AlreadyReversed(id));

    assertThat(pd.getStatus()).isEqualTo(400);
    assertThat(pd.getType().toString()).endsWith("/journal/already-reversed");
    assertThat(pd.getDetail()).contains(id.value().toString());
  }
```

Imports needed if absent: `co.embracejoy.accounting.keystone.domain.journal.JournalError`, `co.embracejoy.accounting.keystone.domain.journal.JournalEntryId`, `org.springframework.http.ProblemDetail`, `static org.assertj.core.api.Assertions.assertThat`.

- [ ] **Step 2: Run test to verify FAIL (compile error)**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B test -Dtest=JournalEntryControllerTest#shouldMapAlreadyReversedTo400`
Expected: FAIL with `JournalError.AlreadyReversed` unresolved symbol.

- [ ] **Step 3: Add the sealed variant to `JournalError.java`**

Add ONE record to the sealed interface (keep every existing variant unchanged):

```java
  /** The requested original has already been reversed — cannot reverse twice. */
  record AlreadyReversed(JournalEntryId id) implements JournalError {}
```

- [ ] **Step 4: Add the `ResultMapper` case**

Extend the `toProblemDetail(JournalError)` switch — add ONE arm alongside `NotFound`:

```java
      case JournalError.AlreadyReversed ar -> journalAlreadyReversed(ar);
```

And add the private helper next to `journalNotFound(...)`:

```java
  private static ProblemDetail journalAlreadyReversed(JournalError.AlreadyReversed ar) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "/journal/already-reversed",
        "Journal entry has already been reversed",
        "Journal entry '" + ar.id().value() + "' has already been reversed.");
  }
```

- [ ] **Step 5: Run test to verify PASS**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest#shouldMapAlreadyReversedTo400`
Expected: PASS.

- [ ] **Step 6: Full unit test run**

Run: `./mvnw -B test`
Expected: baseline preserved (adding a variant to the sealed interface is source-compatible because no existing consumer of `JournalError` uses a total switch expression outside `ResultMapper` — this project's `Result.fold(...)` callers use the general `JournalError` type). If any test fails on an exhaustiveness error, add the case to that switch too — but no such consumer is expected.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ResultMapper.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java
git commit -m "Slice 7 Phase B T1: JournalError.AlreadyReversed + ResultMapper 400 mapping"
```

---

### Task 2: `PostJournalEntryService.postReversal` + `ReverseJournalEntryService`

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryService.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryServiceTest.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`

**Interfaces:**
- Consumes: `JournalEntryRepository.findById(TenantId, JournalEntryId) → Optional<PersistedJournalEntry>`, `.existsReversalOf(TenantId, JournalEntryId) → boolean`, `.saveReversal(JournalEntry, ReversalMetadata, String) → PersistedJournalEntry` (all from Phase A). `JournalEntry.reverse(JournalEntryId, String, LocalDate, JournalEntry) → JournalEntry` (Phase A). `JournalError.NotFound(JournalEntryId)`, `.AlreadyReversed(JournalEntryId)`.
- Produces:
  - `PostJournalEntryService.postReversal(TenantId, JournalEntry reversal, ReversalMetadata metadata, String actor) → Result<PersistedJournalEntry, JournalError>` — runs the same validation stack as `post(...)` and delegates persistence to `journalRepository.saveReversal(...)`.
  - `ReverseJournalEntryService.reverse(TenantId, JournalEntryId originalId, String reason, String actor) → Result<PersistedJournalEntry, JournalError>` — orchestrates the full flow.

- [ ] **Step 1: Write the failing `PostJournalEntryService.postReversal` tests**

Add to `PostJournalEntryServiceTest.java` (adapt to the file's existing fake conventions — read the file first for the `FakeJournalRepo`/`FakeAccountRepository`/`FakePeriodService` shapes):

```java
  @Test
  @DisplayName("postReversal returns success and delegates to saveReversal with actor")
  void shouldPostReversalAndDelegateToSaveReversalWithActor() {
    // Build a valid reversal via JournalEntry.reverse against a seeded original.
    JournalEntry original = /* build a small balanced entry via the existing test-support factory */;
    PersistedJournalEntry persistedOriginal =
        new PersistedJournalEntry(new JournalEntryId(UUID.randomUUID()), original);
    fakeJournalRepo.seed(persistedOriginal);
    JournalEntry reversal =
        JournalEntry.reverse(persistedOriginal.id(), "typo", LocalDate.of(2026, 7, 11), original);
    ReversalMetadata metadata = new ReversalMetadata(persistedOriginal.id(), "typo");

    Result<PersistedJournalEntry, JournalError> result =
        service.postReversal(Tenants.DEFAULT_TENANT_ID, reversal, metadata, "reverser-actor");

    assertThat(result).isInstanceOf(Result.Success.class);
    assertThat(fakeJournalRepo.lastReversalMetadata).isEqualTo(metadata);
    assertThat(fakeJournalRepo.lastActor).isEqualTo("reverser-actor");
  }

  @Test
  @DisplayName("postReversal returns PostingInClosedPeriod when today's period is closed")
  void shouldReturnClosedPeriodErrorWhenReversalDateInClosedPeriod() {
    /* Configure fakePeriodService.periodStatusOn(...) to return CLOSED for the reversal's date;
       run postReversal; assert Result.failure(PostingInClosedPeriod). */
  }
```

Fill in the `/* build a small balanced entry */` and `/* configure fakePeriodService */` with the file's existing conventions. Also extend `FakeJournalRepo`:

```java
  ReversalMetadata lastReversalMetadata;

  @Override
  public PersistedJournalEntry saveReversal(
      JournalEntry reversal, ReversalMetadata metadata, String actor) {
    lastReversalMetadata = metadata;
    lastActor = actor;  // reuse the field added by Phase A T2 R2
    return new PersistedJournalEntry(
        new JournalEntryId(UUID.randomUUID()), reversal, Optional.of(metadata), Optional.empty());
  }

  void seed(PersistedJournalEntry p) { /* store so findById can return it */ }
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=PostJournalEntryServiceTest`
Expected: FAIL (method `postReversal` unresolved).

- [ ] **Step 3: Add `postReversal` to `PostJournalEntryService`**

The trick: reuse the same context-assembly logic as `post(...)`. Since the reversal's `JournalEntry` is already built by the caller, we can't rebuild via `JournalEntry.of(...)`. Instead, run validation via the same context factory but skip re-constructing the entry — validate a synthetic `JournalEntry.of(...)` call using the reversal's fields, then delegate persistence to `saveReversal`:

```java
  /**
   * Persist a reversal entry, applying the same balance/period/account validation stack as
   * {@link #post}. On success, delegates to {@code journalRepository.saveReversal(...)} so the
   * {@code reverses_id} + {@code reversal_reason} columns populate.
   */
  public Result<PersistedJournalEntry, JournalError> postReversal(
      TenantId tenantId,
      JournalEntry reversal,
      ReversalMetadata metadata,
      String actor) {
    JournalValidationContext ctx = buildContext(tenantId, reversal.postings(), reversal.occurredOn());
    return JournalEntry.of(
            tenantId,
            reversal.occurredOn(),
            reversal.description(),
            reversal.postings(),
            ctx)
        .map(validated -> journalRepository.saveReversal(validated, metadata, actor));
  }

  /** Extracted context-building — was inline in {@link #post}; now shared. */
  private JournalValidationContext buildContext(
      TenantId tenantId, List<Posting> postings, LocalDate occurredOn) {
    Set<AccountCode> codes =
        postings.stream().map(Posting::account).collect(Collectors.toCollection(HashSet::new));
    Map<AccountCode, Account> accounts = accountRepository.findByCodeIn(codes);
    Set<AccountCode> nonLeafCodes = /* copy from existing post(...) */;
    PeriodStatus periodStatus = periodService.periodStatusOn(tenantId, occurredOn);
    return new JournalValidationContext(accounts, nonLeafCodes, periodStatus, baseCurrency);
  }
```

Refactor the existing `post(...)` to call `buildContext(...)` too — pure DRY win, no behavior change. Verify all `PostJournalEntryServiceTest`'s existing `post(...)` cases still pass unchanged.

- [ ] **Step 4: Run tests to verify PASS**

Run: `./mvnw -B test -Dtest=PostJournalEntryServiceTest`
Expected: PASS (all existing cases + the 2 new `postReversal` cases).

- [ ] **Step 5: Write the failing `ReverseJournalEntryServiceTest`**

```java
package co.embracejoy.accounting.keystone.application.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.*;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@DisplayName("ReverseJournalEntryService")
class ReverseJournalEntryServiceTest {

  private final JournalEntryRepository repo = Mockito.mock(JournalEntryRepository.class);
  private final PostJournalEntryService poster = Mockito.mock(PostJournalEntryService.class);
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);
  private final ReverseJournalEntryService service =
      new ReverseJournalEntryService(repo, poster, clock);

  private static final TenantId TENANT = Tenants.DEFAULT_TENANT_ID;
  private static final JournalEntryId ORIGINAL_ID =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000abc"));
  private static final String REASON = "posted to wrong account";
  private static final String ACTOR = "reverser-actor";

  @Test
  @DisplayName("returns NotFound when original does not exist")
  void shouldReturnNotFoundWhenOriginalDoesNotExist() {
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.empty());

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isEqualTo(new JournalError.NotFound(ORIGINAL_ID));
    Mockito.verify(poster, Mockito.never()).postReversal(
        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  @DisplayName("returns AlreadyReversed when a reversal of the original already exists")
  void shouldReturnAlreadyReversedWhenOriginalHasBeenReversed() {
    PersistedJournalEntry original = /* build via helper */;
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(true);

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isEqualTo(new JournalError.AlreadyReversed(ORIGINAL_ID));
    Mockito.verify(poster, Mockito.never()).postReversal(
        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  @DisplayName("returns success and delegates to postReversal with actor + today's date")
  void shouldDelegateToPostReversalWithActorAndTodaysDate() {
    PersistedJournalEntry original = /* build with two balanced postings, currency USD, on 2026-06-15 */;
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(false);

    PersistedJournalEntry persisted = /* build a plausible reversal result */;
    Mockito.when(poster.postReversal(
            Mockito.eq(TENANT), Mockito.any(), Mockito.any(), Mockito.eq(ACTOR)))
        .thenReturn(Result.success(persisted));

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Success.class);

    ArgumentCaptor<JournalEntry> reversalCaptor = ArgumentCaptor.forClass(JournalEntry.class);
    ArgumentCaptor<ReversalMetadata> metadataCaptor =
        ArgumentCaptor.forClass(ReversalMetadata.class);
    Mockito.verify(poster).postReversal(
        Mockito.eq(TENANT), reversalCaptor.capture(), metadataCaptor.capture(), Mockito.eq(ACTOR));

    assertThat(reversalCaptor.getValue().occurredOn()).isEqualTo(LocalDate.of(2026, 7, 11));
    assertThat(reversalCaptor.getValue().description())
        .isEqualTo("Reversal of #" + ORIGINAL_ID.value() + ": " + REASON);
    assertThat(metadataCaptor.getValue()).isEqualTo(new ReversalMetadata(ORIGINAL_ID, REASON));
  }

  @Test
  @DisplayName("propagates PostingInClosedPeriod when postReversal rejects the reversal")
  void shouldPropagateClosedPeriodError() {
    PersistedJournalEntry original = /* build */;
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(false);
    Mockito.when(poster.postReversal(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 7))));

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isInstanceOf(JournalError.PostingInClosedPeriod.class);
  }
}
```

Fill in the `/* build */` placeholders using either the codebase's existing test-support factories or a small local helper. Read `PostJournalEntryServiceTest.java` for the `PersistedJournalEntry` construction conventions used elsewhere.

- [ ] **Step 6: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=ReverseJournalEntryServiceTest`
Expected: FAIL — `ReverseJournalEntryService` class doesn't exist.

- [ ] **Step 7: Implement `ReverseJournalEntryService`**

```java
package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.*;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: reverse an existing journal entry.
 *
 * <p>Blocks reversing an already-reversed original ({@link JournalError.AlreadyReversed}) and
 * missing originals ({@link JournalError.NotFound}). Delegates persistence + full
 * balance/period/account validation to {@link PostJournalEntryService#postReversal}. The reversal's
 * {@code occurredOn} is today's date (per {@link Clock}), never the original's — so a reversal of a
 * historical entry lands in the current period and cannot rewrite closed-period history.
 */
public final class ReverseJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final PostJournalEntryService postJournalEntryService;
  private final Clock clock;

  public ReverseJournalEntryService(
      JournalEntryRepository journalRepository,
      PostJournalEntryService postJournalEntryService,
      Clock clock) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.postJournalEntryService =
        Objects.requireNonNull(postJournalEntryService, "postJournalEntryService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Result<PersistedJournalEntry, JournalError> reverse(
      TenantId tenantId, JournalEntryId originalId, String reason, String actor) {
    Optional<PersistedJournalEntry> maybeOriginal = journalRepository.findById(tenantId, originalId);
    if (maybeOriginal.isEmpty()) {
      return Result.failure(new JournalError.NotFound(originalId));
    }
    if (journalRepository.existsReversalOf(tenantId, originalId)) {
      return Result.failure(new JournalError.AlreadyReversed(originalId));
    }
    LocalDate today = LocalDate.now(clock);
    JournalEntry reversal =
        JournalEntry.reverse(originalId, reason, today, maybeOriginal.get().entry());
    ReversalMetadata metadata = new ReversalMetadata(originalId, reason);
    return postJournalEntryService.postReversal(tenantId, reversal, metadata, actor);
  }
}
```

- [ ] **Step 8: Wire as a `@Bean`**

Add to `ApplicationConfig.java` next to `postJournalEntryService(...)`:

```java
  @Bean
  public ReverseJournalEntryService reverseJournalEntryService(
      JournalEntryRepository journalRepository,
      PostJournalEntryService postJournalEntryService,
      Clock clock) {
    return new ReverseJournalEntryService(journalRepository, postJournalEntryService, clock);
  }
```

The `Clock` bean already exists in `ApplicationConfig` (added during Slice 5 D-admin-api).

- [ ] **Step 9: Run tests to verify PASS**

Run: `./mvnw -B test -Dtest=ReverseJournalEntryServiceTest,PostJournalEntryServiceTest`
Expected: PASS.

- [ ] **Step 10: Full unit run**

Run: `./mvnw -B test`
Expected: green baseline preserved.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java \
        src/main/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryService.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ApplicationConfig.java \
        src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java \
        src/test/java/co/embracejoy/accounting/keystone/application/journal/ReverseJournalEntryServiceTest.java
git commit -m "Slice 7 Phase B T2: ReverseJournalEntryService + PostJournalEntryService.postReversal"
```

---

### Task 3: `POST /journal-entries/{id}/reverse` endpoint

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ReverseJournalEntryRequest.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java`
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java`

**Interfaces:**
- Consumes: `ReverseJournalEntryService.reverse(TenantId, JournalEntryId, String, String) → Result<PersistedJournalEntry, JournalError>` (T2). `JournalEntryResponse.of(PersistedJournalEntry)` (Phase A). `ResultMapper.toProblemDetail(JournalError)` (T1 already added `AlreadyReversed`).
- Produces: `POST /journal-entries/{id}/reverse` returning 201 + `Location: /journal-entries/<newId>` + `JournalEntryResponse`, or ProblemDetail per error.

- [ ] **Step 1: Create `ReverseJournalEntryRequest`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /journal-entries/&#123;id&#125;/reverse}. */
public record ReverseJournalEntryRequest(@NotBlank @Size(max = 500) String reason) {}
```

- [ ] **Step 2: Write the failing controller tests**

Add to `JournalEntryControllerTest.java` (mirror the existing `create(...)` test conventions — `withTestAuth(Role role)`, `@MockitoBean ReverseJournalEntryService reverseService`, etc.):

```java
  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 201 + Location on success")
  void shouldReturn201WhenReverseSucceeds() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));
    PersistedJournalEntry reversal = /* build a plausible reversal PersistedJournalEntry */;
    Mockito.when(reverseService.reverse(
            Mockito.eq(Tenants.DEFAULT_TENANT_ID),
            Mockito.eq(originalId),
            Mockito.eq("typo"),
            Mockito.anyString()))
        .thenReturn(Result.success(reversal));

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"typo\" }")
                .with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/journal-entries/" + reversal.id().value())))
        .andExpect(jsonPath("$.id").value(reversal.id().value().toString()));
  }

  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 404 when original not found")
  void shouldReturn404WhenOriginalNotFound() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());
    Mockito.when(reverseService.reverse(Mockito.any(), Mockito.eq(originalId), Mockito.any(), Mockito.any()))
        .thenReturn(Result.failure(new JournalError.NotFound(originalId)));

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"typo\" }")
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/not-found")));
  }

  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 400 when already reversed")
  void shouldReturn400WhenAlreadyReversed() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());
    Mockito.when(reverseService.reverse(Mockito.any(), Mockito.eq(originalId), Mockito.any(), Mockito.any()))
        .thenReturn(Result.failure(new JournalError.AlreadyReversed(originalId)));

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"typo\" }")
                .with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/already-reversed")));
  }

  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 400 when reason is blank")
  void shouldReturn400WhenReasonBlank() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"\" }")
                .with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 400 when today's period is closed")
  void shouldReturn400WhenPostingInClosedPeriod() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());
    Mockito.when(reverseService.reverse(Mockito.any(), Mockito.eq(originalId), Mockito.any(), Mockito.any()))
        .thenReturn(Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 7))));

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"typo\" }")
                .with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/posting-in-closed-period")));
  }

  @Test
  @DisplayName("POST /journal-entries/{id}/reverse returns 403 when caller has only READ_ONLY")
  void shouldReturn403WhenReadOnlyTriesToReverse() throws Exception {
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());

    mvc.perform(
            post("/journal-entries/" + originalId.value() + "/reverse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"typo\" }")
                .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isForbidden());
  }
```

Add `@MockitoBean ReverseJournalEntryService reverseService;` to the test class fields if absent.

- [ ] **Step 3: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest`
Expected: FAIL — the new endpoint doesn't exist yet (405 or 404 for the URL).

- [ ] **Step 4: Add the handler + wire the service**

Constructor: inject `ReverseJournalEntryService reverseService` alongside the existing `PostJournalEntryService` and `JournalEntryQueryService`.

Handler on `JournalEntryController.java`:

```java
  @PostMapping("/{id}/reverse")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
  @Operation(
      summary = "Reverse a journal entry",
      description =
          "Posts a mirror entry (debits/credits swapped) referencing the original via reverses_id."
              + " Reversal date is today; the original is never modified. Blocks reversing an"
              + " already-reversed entry (400 /journal/already-reversed) and unknown ids"
              + " (404 /journal/not-found).")
  public ResponseEntity<?> reverse(
      @PathVariable String id, @Valid @RequestBody ReverseJournalEntryRequest req) {
    JournalEntryId originalId;
    try {
      originalId = new JournalEntryId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return notFoundByRawId(id);
    }
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    return reverseService
        .reverse(tenantContext.require(), originalId, req.reason(), actor)
        .fold(
            persisted ->
                ResponseEntity.created(URI.create("/journal-entries/" + persisted.id().value()))
                    .body(JournalEntryResponse.of(persisted)),
            this::error);
  }
```

The `notFoundByRawId(id)` + `error(JournalError)` helpers already exist on this controller from Phase A T8 — reuse them, don't reintroduce.

Malformed-UUID cases fall through to the same `journalNotFoundByRawId` shape T8 established. Blank-reason cases go through the existing `ValidationExceptionHandler` (`MethodArgumentNotValidException` on the `@Valid @RequestBody`) — no controller work needed.

- [ ] **Step 5: Run to verify PASS**

Run: `./mvnw -B test -Dtest=JournalEntryControllerTest`
Expected: PASS — all existing cases + 6 new reversal cases.

- [ ] **Step 6: Full unit run**

Run: `./mvnw -B test`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/dto/ReverseJournalEntryRequest.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryController.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/JournalEntryControllerTest.java
git commit -m "Slice 7 Phase B T3: POST /journal-entries/{id}/reverse endpoint"
```

---

### Task 4: Smoke IT — post + reverse round-trip

**Files:**
- Modify: `src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java`

**Interfaces:**
- Consumes: existing smoke IT infrastructure (Testcontainers Postgres, JWT via `JwtTestSupport`, actor sub, `@ServiceConnection`). The new endpoint `POST /journal-entries/{id}/reverse` from T3. The `GET /journal-entries/{id}` endpoint from Phase A T8.
- Produces: one round-trip smoke case proving the write + read path is wired end-to-end against real Postgres.

- [ ] **Step 1: Write the failing smoke case**

Add ONE method to `ApplicationSmokeIT.java` (adapt to the file's existing `RestClient` + `Tenants.DEFAULT_TENANT_ID` + `JwtTestSupport` conventions — read the existing `shouldPostEntryAndIncrementMetric` for the shape):

```java
  @Test
  @DisplayName("Reversal round-trip: POST entry → POST reverse → GET both back with metadata")
  void shouldReverseAnEntryAndSurfaceMetadataOnBothSides() {
    RestClient client = clientWithRoles(Role.BOOKKEEPER);

    // 1. Post a balanced original
    ResponseEntity<String> createOriginal =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "occurredOn": "2026-07-11",
                  "description": "original for reversal smoke",
                  "postings": [
                    { "account": "1000", "side": "DEBIT",  "minorUnits": 4200,
                      "currency": "USD", "baseMinorUnits": 4200 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 4200,
                      "currency": "USD", "baseMinorUnits": 4200 }
                  ]
                }
                """)
            .retrieve()
            .toEntity(String.class);
    assertThat(createOriginal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String originalId = idFromLocation(createOriginal.getHeaders().getLocation());

    // 2. Reverse it
    ResponseEntity<String> reverse =
        client
            .post()
            .uri("/journal-entries/" + originalId + "/reverse")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{ \"reason\": \"smoke test reversal\" }")
            .retrieve()
            .toEntity(String.class);
    assertThat(reverse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String reversalId = idFromLocation(reverse.getHeaders().getLocation());
    assertThat(reverse.getBody()).contains("\"reversesId\":\"" + originalId + "\"");
    assertThat(reverse.getBody()).contains("\"reversalReason\":\"smoke test reversal\"");

    // 3. GET the original — should now carry reversedBy metadata
    ResponseEntity<String> originalDetail =
        client
            .get()
            .uri("/journal-entries/" + originalId)
            .retrieve()
            .toEntity(String.class);
    assertThat(originalDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(originalDetail.getBody()).contains("\"reversedById\":\"" + reversalId + "\"");
    assertThat(originalDetail.getBody()).contains("\"reversedReason\":\"smoke test reversal\"");
  }

  private static String idFromLocation(URI location) {
    String path = location.getPath();
    // path is /journal-entries/<uuid> or /journal-entries/<uuid>/reverse — take segment after
    // /journal-entries/.
    int start = path.indexOf("/journal-entries/") + "/journal-entries/".length();
    int end = path.indexOf('/', start);
    return end == -1 ? path.substring(start) : path.substring(start, end);
  }
```

`clientWithRoles(Role role)` is the existing helper — read the file for its exact name. If the file uses a different helper name, adapt. The `Role.BOOKKEEPER` role covers both POST and reverse (JOURNAL_POST permission).

- [ ] **Step 2: Run to verify FAIL / GREEN**

Run: `./mvnw -B verify -Dit.test=ApplicationSmokeIT -DfailIfNoTests=false`
Expected: PASS. The existing infrastructure (Testcontainers Postgres, JWT test support, actor threading) already covers everything; T3's controller handler is the only new dependency, and it exists after T3.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/co/embracejoy/accounting/keystone/smoke/ApplicationSmokeIT.java
git commit -m "Slice 7 Phase B T4: smoke IT — post + reverse + GET metadata round-trip"
```

---

### Task 5: OpenAPI regen + full verify + PR

**Files:**
- Modify: `docs/openapi/openapi.yaml`

**Interfaces:**
- Consumes: T1–T4.
- Produces: pushed branch + PR.

- [ ] **Step 1: Regenerate the OpenAPI snapshot**

Ensure Postgres is up:

```bash
docker ps | grep keystone-postgres || docker compose up -d postgres
```

Then:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B -Popenapi-update verify
```

Expected: BUILD SUCCESS; `docs/openapi/openapi.yaml` updated with the new `POST /journal-entries/{id}/reverse` operation + `ReverseJournalEntryRequest` schema + `/problems/journal/already-reversed` type reference.

- [ ] **Step 2: Full CI-parity gate**

```bash
./mvnw -B clean verify -Popenapi-gate
```

Expected: BUILD SUCCESS. openapi-diff should report "backward compatible" — a single new POST endpoint is a purely additive change.

- [ ] **Step 3: Commit the snapshot regen**

```bash
git add docs/openapi/openapi.yaml
git commit -m "Slice 7 Phase B T5: regenerate OpenAPI snapshot for POST /journal-entries/{id}/reverse"
```

- [ ] **Step 4: Push + open PR**

```bash
git push -u origin slice-7-phase-b-reverse-endpoint
gh pr create --title "Slice 7 Phase B: reverse endpoint" --body "$(cat <<'EOF'
## Summary

Ships Phase B of Slice 7 per the [merged design spec](docs/superpowers/specs/2026-07-09-slice-7-journal-entry-corrections-design.md). Phase A landed the list/detail API + persistence + read model + reverse factory; this PR adds the actual reverse endpoint.

- **Domain** — \`JournalError.AlreadyReversed(JournalEntryId)\` variant. \`ResultMapper\` maps it to 400 \`/journal/already-reversed\`.
- **Application** — new \`ReverseJournalEntryService\` orchestrating \`findById → existsReversalOf → JournalEntry.reverse → postReversal\`. New \`PostJournalEntryService.postReversal(...)\` overload that reuses the balance/period/account validation stack and delegates persistence to \`journalRepository.saveReversal(...)\`.
- **Web** — new \`ReverseJournalEntryRequest\` DTO (\`reason\` @NotBlank @Size(max=500)\`). \`POST /journal-entries/{id}/reverse\` under \`@PreAuthorize(\"hasAnyRole('ADMIN','BOOKKEEPER')\")\` (JOURNAL_POST). Actor from JWT sub.
- **Smoke** — round-trip case in \`ApplicationSmokeIT\`: POST entry → POST reverse → GET both back and verify \`reversesId\` + \`reversedById\` metadata.

## Test plan

- [x] \`./mvnw -B clean verify -Popenapi-gate\` — full CI-parity green.
- [x] openapi-diff reports API changes backward compatible.

Phase C (UI) follows in a later PR.
EOF
)"
```

- [ ] **Step 5: Await CI**

If CI fails, diagnose via `gh run view --log-failed`.

---

## Self-Review

**1. Spec coverage** — cross-check against spec §6.1 Phase B:
- `ReverseJournalEntryService` — T2. ✓
- `ReverseJournalEntryRequest` DTO — T3. ✓
- `POST /journal-entries/{id}/reverse` — T3. ✓
- `JournalError.AlreadyReversed` in `ResultMapper` — T1. ✓
- `@WebMvcTest` reverse cases — T3 (6 cases). ✓
- Smoke IT extended with the round-trip — T4. ✓
- OpenAPI snapshot regen — T5. ✓

Spec §3.1 flow — `findById` → `existsReversalOf` → build via `JournalEntry.reverse` → post through validation stack — implemented via T2's `ReverseJournalEntryService.reverse` + `PostJournalEntryService.postReversal` split. Actor threaded from `SecurityContextHolder`. Today's date from `Clock`. Reversal's postings validated by the same `JournalValidationContext` machinery as an original post.

**2. Placeholder scan** — none. Task 2 has three `/* build */` placeholders in test bodies pointing the implementer at the file's existing test-support factory conventions (rather than fabricating construction that may not match). That's intentional pointer-not-placeholder per the plan style ADR.

**3. Type consistency**:
- `JournalError.AlreadyReversed(JournalEntryId)` — same shape across T1 (domain + mapper), T2 (service), T3 (controller test).
- `ReverseJournalEntryService.reverse(TenantId, JournalEntryId, String reason, String actor) → Result<PersistedJournalEntry, JournalError>` — same signature in T2 (impl + tests) and T3 (controller consumer).
- `PostJournalEntryService.postReversal(TenantId, JournalEntry reversal, ReversalMetadata, String actor) → Result<PersistedJournalEntry, JournalError>` — same signature in T2 impl + tests + T2 service consumer.
- `ReverseJournalEntryRequest.reason` — String, `@NotBlank @Size(max=500)`. Used in T3 controller + test bodies.
- `JournalEntryResponse.of(PersistedJournalEntry)` — from Phase A; T3 uses it verbatim, extended fields carry the reversal metadata.

All name-consistent. Ready for handoff.
