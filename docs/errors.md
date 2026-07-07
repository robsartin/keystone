# API error reference

Every error keystone returns is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
*problem detail* — a JSON object with a stable `type` URI, a human `title`, the
HTTP `status`, a `detail` message describing the specific failure, and the
`instance` (the request path). Error responses are served with
`Content-Type: application/problem+json`.

```json
{
  "type": "https://embracejoy.co/problems/journal/unbalanced",
  "title": "Journal entry is not balanced",
  "status": 400,
  "detail": "Sum of debits (10000 USD) does not equal sum of credits (9000 USD).",
  "instance": "/journal-entries"
}
```

**Program against the `type`, show the `detail`.** The `type` URI is the stable,
machine-readable discriminator — branch your integration logic on it. The
`title` is a fixed short label for the category; the `detail` is human-facing and
its wording may change between releases, so don't parse it.

## Stability promise

The `type` URIs on this page are **part of keystone's public contract.** Renaming
or removing one, or changing the HTTP status attached to it, is a breaking change
and is gated the same way the wire format is — see
[ADR-0006](adr/0006-openapi-gates.md) and the OpenAPI diff check in CI. The
`ProblemDetail` schema itself is declared in the OpenAPI spec
([`docs/openapi/openapi.yaml`](openapi/openapi.yaml)); this page is the
human-readable companion to it.

New `type` URIs may be *added* in a minor release (a new failure mode is not a
break), so treat any unrecognized `type` as a generic `4xx`/`5xx` per its
`status` and surface the `detail`.

All URIs share the base `https://embracejoy.co/problems`. The URI is an
identifier, not a live link — nothing is served at that address.

---

## Journal entry errors

Returned by `POST /journal-entries`. All are `400 Bad Request` — the request was
understood but the entry it describes is not a valid posting.

| `type` (relative to the base) | title | cause | resolution |
|---|---|---|---|
| `/journal/no-postings` | Journal entry has no postings | The entry had an empty `postings` array. | Include at least one posting. (A well-formed double entry has at least two.) |
| `/journal/unbalanced` | Journal entry is not balanced | Sum of debits ≠ sum of credits, measured in the base currency. | Adjust amounts so total debits equal total credits. |
| `/journal/mixed-currencies` | Journal entry mixes currencies | Postings reference more than one transaction currency in a slice that requires a single one. | Split into one entry per transaction currency, or use base-anchored multi-currency postings. |
| `/journal/account-not-found` | Posting references an unknown account | A posting's `account` code does not exist in the chart of accounts. | Create the account first (`POST /accounts`) or fix the code. |
| `/journal/account-inactive` | Posting references a deactivated account | The target account exists but has been deactivated. | Post to an active account, or reactivate it (`POST /accounts/{code}/reactivate`). |
| `/journal/account-not-a-leaf` | Posting targets a non-leaf account | The account has child accounts; only leaf accounts can be posted to. | Post to one of its leaf descendants instead. |
| `/journal/account-currency-mismatch` | Posting currency does not match account currency | A posting's `currency` differs from the account's fixed currency. | Use the account's own currency, or post to an account denominated in the currency you need. |
| `/journal/posting-in-closed-period` | Posting falls in a closed period | `occurredOn` lands in a calendar month that has been closed. | Reopen the period (`POST /periods/{yyyymm}/reopen`) or date the entry into an open period. |
| `/journal/base-currency-mismatch` | Posting baseAmount currency does not match the configured base | A posting's `baseMinorUnits` were interpreted in a currency other than the server's configured base. | Express `baseMinorUnits` in the configured base currency (default `USD`). |
| `/journal/overflow` | Posting sum overflowed | Summing one side of the entry exceeded `Long.MAX_VALUE` minor units. | Split the entry into smaller amounts. (This is a guardrail against absurd inputs.) |

### Example bodies

`/journal/no-postings`

```json
{
  "type": "https://embracejoy.co/problems/journal/no-postings",
  "title": "Journal entry has no postings",
  "status": 400,
  "detail": "A journal entry must contain at least one posting.",
  "instance": "/journal-entries"
}
```

`/journal/unbalanced`

```json
{
  "type": "https://embracejoy.co/problems/journal/unbalanced",
  "title": "Journal entry is not balanced",
  "status": 400,
  "detail": "Sum of debits (10000 USD) does not equal sum of credits (9000 USD).",
  "instance": "/journal-entries"
}
```

`/journal/mixed-currencies`

```json
{
  "type": "https://embracejoy.co/problems/journal/mixed-currencies",
  "title": "Journal entry mixes currencies",
  "status": 400,
  "detail": "Postings reference multiple currencies: [EUR, USD]. Multi-currency journal entries are not supported in this slice.",
  "instance": "/journal-entries"
}
```

`/journal/account-not-found`

```json
{
  "type": "https://embracejoy.co/problems/journal/account-not-found",
  "title": "Posting references an unknown account",
  "status": 400,
  "detail": "Account code '9999' does not exist.",
  "instance": "/journal-entries"
}
```

`/journal/account-currency-mismatch`

```json
{
  "type": "https://embracejoy.co/problems/journal/account-currency-mismatch",
  "title": "Posting currency does not match account currency",
  "status": 400,
  "detail": "Account '3000' uses USD but the posting amount uses EUR.",
  "instance": "/journal-entries"
}
```

---

## Account errors

Returned by the `/accounts` endpoints (`POST /accounts`, `PATCH /accounts/{code}`,
the (de)activation endpoints).

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/account/code-already-exists` | Account code already exists | 400 | Creating an account with a code already in the chart. | Choose a different code, or update the existing account. |
| `/account/not-found` | Account not found | 404 | Referenced a code that isn't in the chart. | Create the account, or correct the code. |
| `/account/parent-not-found` | Parent account not found | 400 | `parentCode` points at an account that doesn't exist. | Create the parent first, or drop/fix `parentCode`. |
| `/account/cycle-would-be-created` | Account hierarchy would form a cycle | 400 | Re-parenting would make an account its own ancestor. | Choose a parent that isn't a descendant of the account. |
| `/account/code-in-use-by-posting` | Account code already in use | 400 | Reusing a code that historical postings already reference. | Pick a different code; codes bound to postings are immutable. |

### Example body

`/account/not-found`

```json
{
  "type": "https://embracejoy.co/problems/account/not-found",
  "title": "Account not found",
  "status": 404,
  "detail": "No account with code '9999'.",
  "instance": "/accounts/9999"
}
```

---

## Period errors

Returned by the period-close/reopen endpoints (`POST /periods/{yyyymm}/close`,
`POST /periods/{yyyymm}/reopen`, `GET /periods/{yyyymm}`).

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/period/not-sequentially-closable` | Period close is out of order | 400 | Tried to close a month while an earlier month with postings is still open. | Close the earliest open active month first, then work forward. |
| `/period/not-most-recently-closed` | Period reopen requires the most-recently-closed period | 400 | Tried to reopen a month that isn't the latest closed one. | Reopen the most-recently-closed month first. |
| `/period/not-found` | Period not found | 404 | Looked up a period row that doesn't exist. | Most months are implicitly `OPEN` and have no row — a 404 here means "open, never closed". |

### Example body

`/period/not-sequentially-closable`

```json
{
  "type": "https://embracejoy.co/problems/period/not-sequentially-closable",
  "title": "Period close is out of order",
  "status": 400,
  "detail": "Cannot close 2026-03; close 2026-01 (or an earlier month) first.",
  "instance": "/periods/2026-03/close"
}
```

---

## Validation errors

Returned by **any** endpoint when the request fails Bean Validation before it
reaches the domain — a missing required field, a value out of range, a
malformed enum, or a path/query parameter that can't be converted to its
expected type. All share a single `type`.

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/validation` | Request validation failed | 400 | One or more request fields violated a constraint (`@NotNull`, `@NotBlank`, `@Pattern`, `@PositiveOrZero`, a type mismatch, …). | Read `detail` — it lists each offending field and what it expected — and fix the request. |

The `detail` concatenates every field violation, `field: message`, joined by
`; ` so a single response reports all problems at once:

```json
{
  "type": "https://embracejoy.co/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "postings: postings must not be empty; description: description is required",
  "instance": "/journal-entries"
}
```

---

## See also

- [Quick-start guide](quick-start.md) — a hands-on walkthrough, including three of these failures end to end.
- [Concepts › Errors as values](concepts.md#errors-as-values) — why keystone models errors as `Result` values internally and translates them at the HTTP boundary.
- [`docs/openapi/openapi.yaml`](openapi/openapi.yaml) — the machine-readable API spec, where the `ProblemDetail` schema is declared.
- [ADR-0004](adr/0004-result-type-and-problem-details.md) — the Result-type-and-ProblemDetail decision.
