# Slice 7 — Journal Entry Corrections (reverse + browse) — design

**Date:** 2026-07-09
**Refs:** foundation §11 (Slice 5 completed multi-tenancy + admin UI; Slice 6 completed multi-currency; this slice adds bookkeeping correction primitives).

**Scope:** Add reverse-entry semantics + a journal-entry browsing UI. Ships in three phases (A: read + list API + persistence; B: reverse API; C: UI). Each phase is its own PR.

## 1. Architecture

The slice extends existing pieces rather than introducing new subsystems:

- **Domain** gains `JournalEntry.reverse(reason, today)` — a factory that returns a new `JournalEntry` with debit/credit legs swapped, `reversesId` set, and `description = "Reversal of #<id>: <reason>"`. `JournalEntryError` gains `AlreadyReversed(JournalEntryId)` and `NotFound(JournalEntryId)`. The original entry is never mutated — reversal is a new aggregate that references the original by id.
- **Application** gains `ReverseJournalEntryService` (already-reversed check, then delegates the actual posting to the existing `PostJournalEntryService` — reuse the balance / period / account validation stack) and `JournalEntryQueryService` (thin, delegates to a read model).
- **Persistence** — migration `V11__journal_entry_reversal.sql` adds `reverses_id UUID NULL` (with composite FK preserving tenant isolation) + `reversal_reason TEXT NULL` on `journal_entries`, plus an index on `(tenant_id, reverses_id)` for fast "was this reversed?" lookups.
- **Read model** — new `JournalEntryReadModel` port in `domain/journal/` with a JdbcClient adapter, per the `TrialBalanceReadModel` (Slice 4) precedent. Cursor pagination uses `id > cursor`; UUID v7 IDs sort by time so this doubles as chronological order.
- **Web** — `JournalEntryController` gains `GET` (list, cursor + filters), `GET /{id}` (detail with reversal metadata via LEFT JOIN), `POST /{id}/reverse`. All new endpoints under `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")` (JOURNAL_POST permission).
- **UI** — new `infrastructure.web.ui.journal.*` mirroring the D-admin-ui pattern: `JournalEntryUiController` + list page + detail page + reverse dialog. HTMX row swaps + `<section>` top-level + `closest tr` targets + alert-region for 4xx.

**No new ADRs.** A cursor-pagination ADR is worth writing when a second surface adopts the pattern; flagged as a follow-up.

## 2. Components + file layout

```
src/main/java/co/embracejoy/accounting/keystone/
  domain/journal/
    JournalEntry.java                    # add reverse(reason, today) factory
    JournalEntryError.java               # add AlreadyReversed + NotFound
    JournalEntryQuery.java               # NEW — cursor + filter record
    JournalEntryReadModel.java           # NEW port interface
    JournalEntryRepository.java          # add findById, existsReversalOf
  application/journal/
    ReverseJournalEntryService.java      # NEW
    JournalEntryQueryService.java        # NEW
    PostJournalEntryService.java         # unchanged (reused by reverse flow)
  infrastructure/
    persistence/journal/
      JournalEntryEntity.java            # add reverses_id + reversal_reason columns
      JournalEntryEntityMapper.java      # map new columns
      JournalEntryRepositoryAdapter.java # add findById, existsReversalOf
      JournalEntryJdbcReadModel.java     # NEW — JdbcClient, cursor + filter SQL
    web/
      JournalEntryController.java        # add GET list, GET {id}, POST {id}/reverse
      dto/
        ListJournalEntriesResponse.java  # NEW — { items, nextCursor }
        JournalEntryResponse.java        # extend with reversedAt/By, reversesId
        ReverseJournalEntryRequest.java  # NEW — { reason }
    web/ui/journal/
      JournalEntryUiController.java      # NEW — list, detail, reverse handler
      dto/
        JournalEntryFilterForm.java      # NEW — from, to, account, q, min, max
        ReverseJournalEntryForm.java     # NEW — reason

src/main/resources/db/migration/
  V11__journal_entry_reversal.sql        # NEW

src/main/resources/templates/
  journal-entries.html                   # NEW — list with filters + Load more
  journal-entry-detail.html              # NEW — detail + Reverse button
  fragments/journal-entry-row.html       # NEW — one <tr>, reused by list + load-more
  fragments/reverse-dialog.html          # NEW — HTMX-loaded modal with reason field

src/test/java/co/embracejoy/accounting/keystone/
  domain/journal/
    JournalEntryReversalTest.java        # NEW
    JournalEntryQueryTest.java           # NEW
  application/journal/
    ReverseJournalEntryServiceTest.java  # NEW
    JournalEntryQueryServiceTest.java    # NEW
  infrastructure/
    persistence/journal/
      JournalEntryRepositoryAdapterIT.java   # extend for reverses_id round-trip
      JournalEntryJdbcReadModelIT.java   # NEW — cursor + filter SQL against Postgres
    web/
      JournalEntryControllerTest.java    # extend with list/detail/reverse cases
    web/ui/journal/
      JournalEntryUiControllerTest.java  # NEW
  smoke/
    ApplicationSmokeIT.java              # add one reverse-round-trip case
  ui/e2e/
    AdminUiE2EIT.java                    # add one reverse flow
```

**Unit boundaries:**

- `JournalEntryQuery` is a record (no logic). Filter fields are `Optional<X>`; the repository composes the WHERE clause. Keeps the read model focused on SQL.
- `JournalEntryReadModel` is a port in `domain/journal/`; the JDBC adapter lives in `infrastructure/persistence/journal/`. Domain doesn't know about JDBC (ADR-0002).
- `ReverseJournalEntryService` doesn't duplicate posting logic — it constructs a reversal `JournalEntry` via the domain factory, then hands it to `PostJournalEntryService.post(...)`. Balance / period / account validation is reused unchanged.
- UI controller stays thin per the D-admin-ui pattern: pattern-match `Result` inline, `HttpServletResponse.setStatus(...)` for failures, no wrapper exceptions.

## 3. Data flow

### 3.1 Reverse an entry (API)

```
POST /journal-entries/{id}/reverse
Authorization: Bearer <JWT>
Body: { "reason": "posted to wrong account" }

  ▼ JournalEntryController.reverse(id, req)
  │  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')") → 403 if not
  │
  ▼ ReverseJournalEntryService.reverse(tenantId, id, reason, actor)
  │  1. journalRepo.findById(tenantId, id) → Optional<PersistedJournalEntry>
  │     ├─ empty       → Result.failure(JournalError.NotFound(id))
  │     └─ present     → continue
  │  2. journalRepo.existsReversalOf(tenantId, id) → boolean
  │     └─ true        → Result.failure(JournalError.AlreadyReversed(id))
  │  3. reversal = original.entry.reverse(reason, clock.today())
  │       (swaps debit/credit legs, sets reversesId + reversalReason,
  │        description = "Reversal of #<id>: <reason>", copies currencies)
  │  4. postJournalEntryService.post(tenantId, reversal, actor)
  │       ↳ full existing balance / period / account validation stack.
  │         If reversal's posting date falls in a closed period,
  │         that path returns Result.failure(PostingInClosedPeriod).
  │  5. return Result.success(persistedReversal)
  │
  ▼ Controller.fold(success, error)
     201 Created + Location: /journal-entries/<newId> + JournalEntryResponse
     or 4xx ProblemDetail via existing ResultMapper (with new AlreadyReversed variant)
```

**Actor sub** comes from `SecurityContextHolder.getContext().getAuthentication().getName()` — same pattern as `UserRoleUiController` (Slice 5).

### 3.2 List entries (API)

```
GET /journal-entries?from=2026-06-01&to=2026-06-30&account=1000&limit=50&after=<uuid>

  ▼ JournalEntryController.list(from, to, account, q, amountMin, amountMax, limit, after)
  │  Bind optional query params into a JournalEntryQuery record.
  │  Default limit = 50, max = 200.
  │
  ▼ JournalEntryQueryService.findMany(tenantId, query)
  │  └─ delegates to JournalEntryReadModel.findMany(tenantId, query)
  │
  ▼ JournalEntryJdbcReadModel.findMany(...)
  │  SELECT e.id, e.occurred_on, e.description, e.posted_at,
  │         e.reverses_id, e.reversal_reason,
  │         SUM(p.debit_minor)  AS total_debit,
  │         SUM(p.credit_minor) AS total_credit
  │  FROM journal_entries e
  │  JOIN postings p ON p.journal_entry_id = e.id
  │  WHERE e.tenant_id = :tenant
  │    AND (:from IS NULL OR e.occurred_on >= :from)
  │    AND (:to   IS NULL OR e.occurred_on <= :to)
  │    AND (:acct IS NULL OR EXISTS (
  │            SELECT 1 FROM postings x
  │            WHERE x.journal_entry_id = e.id
  │              AND x.account_code = :acct))
  │    AND (:q    IS NULL OR e.description ILIKE '%' || :q || '%')
  │    AND (:after IS NULL OR e.id > :after)
  │  GROUP BY e.id
  │  HAVING (:min IS NULL OR SUM(p.debit_minor) >= :min)
  │     AND (:max IS NULL OR SUM(p.debit_minor) <= :max)
  │  ORDER BY e.id ASC        -- UUID v7 sorts by time
  │  LIMIT :limit + 1;
  │
  │  If limit+1 rows returned, drop the last and set nextCursor = last.id.
  │
  ▼ 200 OK
     { "items": [ … JournalEntryResponse … ], "nextCursor": "<uuid|null>" }
```

Amount filters MUST be in `HAVING`, not `WHERE`, because `SUM(p.debit_minor)` is aggregated. Called out here so the IT sanity-checks that: a low-value posting on a large-total entry would pass a naïve `WHERE p.debit_minor >=` but must fail the `HAVING SUM(...) >=`.

### 3.3 Get one (API)

```
GET /journal-entries/{id}

  ▼ JournalEntryReadModel.findById(tenantId, id)
  │  LEFT JOIN journal_entries r ON r.reverses_id = e.id
  │  so the response can include reversedAt / reversedBy / reversedById
  │  when this entry has been reversed.
  │
  ▼ 200 with JournalEntryResponse
       (or 404 with JournalError.NotFound)
```

The detail response includes both directions of the reversal graph:

- `reversesId`: if this entry IS a reversal, what did it reverse?
- `reversedById`, `reversedAt`, `reversedBy`, `reversalReason`: if this entry WAS reversed, by whom / when / why?

### 3.4 UI list + reverse

```
GET /admin/ui/journal-entries?...
  ▼ UiController: pattern-match Result → journal-entries.html
     Filters render as a form with GET method; submit reloads the page.
     "Load more" = HTMX GET /admin/ui/journal-entries?after=…
       hx-target="tbody" hx-swap="beforeend" (appends new rows).

GET /admin/ui/journal-entries/{id}
  ▼ UiController → journal-entry-detail.html
     "Reverse" button HTMX-loads fragments/reverse-dialog.html into #modal-region
     (aria-labelled dialog with focus trapping via existing HTMX after-swap listener).

POST /admin/ui/journal-entries/{id}/reverse (form-urlencoded reason=…)
  ▼ UiController.reverse(id, form, req, resp)
     Pattern-match Result:
       success → HX-Redirect: /admin/ui/journal-entries/<newReversalId>
                 (whole-page nav to the fresh reversal's detail page)
       failure → return "fragments/alert :: alert" + setStatus(4xx)
                 → layout.html's 4xx retarget listener swaps into #alert-region
```

### 3.5 Closed-period + double-reverse errors

Both surface as `Result.failure` bubbling through the existing `ResultMapper`:

- `AlreadyReversed(id)` → 400, `/problems/journal/already-reversed`, "Journal entry '&lt;id&gt;' has already been reversed."
- `NotFound(id)` → 404, `/problems/journal/not-found`, "No journal entry with id '&lt;id&gt;'."
- `PostingInClosedPeriod(date)` → 400 (existing mapper entry). Message already reads "reopen the period before posting" — accurate for reversals too.

## 4. Error handling

Domain errors flow through the existing `Result<T, JournalError>` + `ResultMapper` machinery from Slice 2/3/4:

**New error variants:**

- `JournalError.AlreadyReversed(JournalEntryId id)` — 400 / `/problems/journal/already-reversed`.
- `JournalError.NotFound(JournalEntryId id)` — 404 / `/problems/journal/not-found`. Currently absent (there's no GET by id today).

**Reused error paths** (no changes needed):

- `PostingInClosedPeriod` — fires if today's date lands in a closed period. Existing message clarifies "reopen the period before posting" — accurate for reversals too.
- Any account / currency / balance error from `PostJournalEntryService.post(...)` — the reversal's postings are validated by the same stack. Rare in practice (reversing an entry whose account was deactivated between the original post and now) but the existing `AccountInactive` error covers it.

**Bad input at the API boundary** (`ReverseJournalEntryRequest.reason` missing / blank): `MethodArgumentNotValidException` is already caught by `ValidationExceptionHandler` (`@RestControllerAdvice(annotations = RestController.class)` from Slice 5 T9) → 400 ProblemDetail. No new handler needed.

**UI layer** — same ADR-0004-strict pattern as Slice 5 T7-T9:

- `JournalEntryUiController` pattern-matches `Result` inline: success returns view name; failure calls `HttpServletResponse.setStatus(...)` and returns `"fragments/alert :: alert"`. No wrapper exceptions.
- Bean-Validation on `ReverseJournalEntryForm.reason` (`@NotBlank @Size(max=500)`) → `MethodArgumentNotValidException` → existing `UiExceptionHandler.onValidationError(...)` handles it (HTMX gets fragment, plain nav gets `error.html`).

**One new `UiResultMapper` overload:** `toAlertView(JournalError)`. Delegates to `ResultMapper.toProblemDetail(JournalError)` (the JSON-side mapper), lifts title + detail. Same shape as the `TenantError` / `SecurityError` overloads from Slice 5.

**OpenAPI:** every new error type gets a matching `ProblemDetail` schema. Snapshot regenerated via the `openapi-update` profile before opening the PR (per ADR-0006).

## 5. Testing strategy

Three layers, mirroring what the existing slices established:

**5.1 Domain unit tests** — pure JUnit, no Spring:

- `JournalEntryReversalTest`: `reverse(reason, today)` produces an entry with debit/credit legs swapped, `reversesId` set, `description` matching the prefix, `occurredOn` = today, currencies preserved leg-by-leg. Rejects blank reason.
- `JournalEntryQueryTest`: filter record captures optional values; `Optional.empty()` for all fields means "no filter."

**5.2 Application unit tests** — Mockito:

- `ReverseJournalEntryServiceTest`: original not found → `NotFound`; original already reversed → `AlreadyReversed`; happy path → constructs reversal via domain factory, hands to `PostJournalEntryService`, returns success; posting failure (e.g., today is in a closed period) → error bubbles.
- `JournalEntryQueryServiceTest`: delegates to read model; verifies filter / cursor pass-through.

**5.3 Persistence IT** — Testcontainers Postgres:

- `JournalEntryRepositoryAdapterIT`: extend for `reverses_id` + `reversal_reason` round-trip (persist → find → assert fields), `existsReversalOf(...)` boolean semantics.
- `JournalEntryJdbcReadModelIT`: seed ~10 entries across tenants + dates + accounts + amounts → assert each filter narrows correctly and combinations AND together. Cursor pagination: request `limit=3`, cursor forward, assert stable ordering + correct `nextCursor` + empty `nextCursor` on last page. Tenant isolation: cross-tenant query returns zero rows (RLS + WHERE together).
- **HAVING vs WHERE for amount** — one test explicitly pairs `amountMin` with a small-value single posting on a large-total entry, so the test would fail if the SQL got the aggregation-position wrong.

**5.4 API @WebMvcTest** — extends `JournalEntryControllerTest`:

- Reverse happy path: 201 + Location + response body with `reversesId`.
- Reverse `AlreadyReversed`: 400 + `/problems/journal/already-reversed`.
- Reverse blank reason: 400 (Bean Validation).
- Reverse without JOURNAL_POST role: 403.
- Reverse in closed period: 400 + existing `PostingInClosedPeriod` type.
- List: happy path (2 entries → both returned + cursor null), pagination (cursor round-trip), each filter individually + combined.
- Get by id: happy path, 404 for unknown, response includes reversal metadata for a reversed entry.

**5.5 UI @WebMvcTest** — new `JournalEntryUiControllerTest` following T6-T9 pattern (filters-ON minimal `SecurityFilterChain` + `@EnableMethodSecurity` + `.with(oidcLogin()...)`):

- List renders (happy path, empty state, filter form present).
- Detail renders (with / without reversal metadata).
- Reverse POST happy path → `HX-Redirect` header (not a fragment) since UI redirects on success.
- Reverse validation failure → alert fragment + 400.
- Reverse wrong role → alert fragment + 403 (via `UiExceptionHandler`).

**5.6 Smoke IT** — extend `ApplicationSmokeIT`:

- One new test: post an entry, GET it back, reverse it, GET the reversal, GET the original again (expect reversal metadata populated). Proves the full stack.

**5.7 Playwright + axe E2E** — extend `AdminUiE2EIT`:

- One new flow: navigate to journal-entries list, filter by date, click into detail, click Reverse, enter reason, submit, land on the reversal's detail page, back-navigate to the original and verify "Reversed by" metadata visible. axe assertions on every page state.

**Coverage impact:** JaCoCo 85% line should hold — most new code is domain / application (well-tested) or thin controller glue. `JournalEntryJdbcReadModel` SQL isn't measured by JaCoCo (it's data) but is IT-tested. Sanity-check the delta in the PR.

## 6. Rollout + non-goals

### 6.1 What ships across the three phases

- **Phase A** — migration V11, `JournalEntry` reverse factory, `JournalEntryError.{AlreadyReversed, NotFound}`, `JournalEntryQuery` record, `JournalEntryReadModel` port + JdbcClient adapter, `JournalEntryQueryService`, repository extensions, `GET /journal-entries` (list), `GET /journal-entries/{id}` (detail), extended `JournalEntryResponse` DTO. All unit + IT tests. OpenAPI snapshot regen.
- **Phase B** — `ReverseJournalEntryService`, `ReverseJournalEntryRequest` DTO, `POST /journal-entries/{id}/reverse`, `JournalError.AlreadyReversed` in `ResultMapper`. `@WebMvcTest` reverse cases. Smoke IT extended with the round-trip. OpenAPI snapshot regen.
- **Phase C** — `JournalEntryUiController` + form DTOs, `journal-entries.html` + `journal-entry-detail.html` + `fragments/journal-entry-row.html` + `fragments/reverse-dialog.html`, `UiResultMapper.toAlertView(JournalError)`. `@WebMvcTest` UI slice. `AdminUiE2EIT` extended with the reverse flow. No new ADRs.

Each phase gets its own PR; each PR's smoke commit updates the OpenAPI snapshot (Phase A and B only — Phase C's `@Controller` UI doesn't touch the snapshot).

### 6.2 Deferrals / follow-ups

- **Cursor-pagination ADR** — deferred until a second surface adopts the pattern. Documented in this spec, not codified as an ADR yet.
- **Reason character-set / language rules** — `@Size(max=500)` only. No profanity filter, no language detection, no translation.
- **Amount filter operator syntax** — `amountMin` / `amountMax` only. No `?amount>=4500` string parsing.
- **Sort options** — always `ORDER BY id ASC` (chronological via UUID v7). No `?sort=amount` or `?sort=description`.
- **Full-text search** — `q` uses simple `ILIKE '%q%'`. No Postgres `tsvector`, no ranking, no highlight.
- **Batch reversal** — no "reverse these 10 entries" endpoint. One at a time.
- **Journal-book / ledger-book reports** — separate slice. Not a substitute for a proper journal-book PDF.

### 6.3 Non-goals (explicit YAGNI)

- Void (soft-delete) — ruled out in brainstorm Q2.
- Editing description or `occurredOn` on a posted entry.
- Reversal approval workflow (multi-step, requester + approver).
- Reversal reason as a controlled vocabulary (dropdown of "correction / typo / duplicate / …").
- CSV export of query results.
- Streaming API (SSE / websocket) for live entry feeds.
- Entry attachments (invoice PDF, receipt image).

### 6.4 Migration compatibility

V11 is additive (nullable columns + one index). Safe against a running production; rolling deploy compatible.
