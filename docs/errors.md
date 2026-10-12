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

## Status policy

The HTTP status says what kind of failure it is
([ADR-0024](adr/0024-http-status-policy-for-domain-errors.md)):

| Status | Meaning | Retry the same request? |
|---|---|---|
| `400` | **Invalid input.** The request is wrong on its own: decidable from the request and static configuration, with no stored state read. A name in the body that resolves to nothing (an unknown posting account, an unknown parent account) is also `400`. | No. Fix the request. |
| `404` | **Missing target.** The resource the URL addresses does not exist in your tenant. Another tenant's resource is indistinguishable from an absent one, so it is `404`, never `403`. | No. |
| `409` | **State conflict.** The request is well-formed and everything it references exists, but it conflicts with the current state of a resource. | Yes, once that state changes (reopen the period, reactivate the account, add a second Admin, ...). |
| `401` | **Not authenticated.** No valid credential. | After authenticating. |
| `403` | **Not authorised.** Valid credential, but your role (or an unrecognised tenant claim) grants no access to the endpoint. | No. |

### Change on 2026-10-11: eleven problem types moved from 400 to 409

These `type` URIs now carry status `409`; they were `400` before. The `type`
URIs themselves are unchanged. This is a deliberate, breaking change to the
status line and to the `status` field, made under the stability promise below.
Clients that branch on `type` are unaffected; clients that branch on `400` for
these cases must also handle `409`.

`/journal/already-reversed`, `/journal/posting-in-closed-period`,
`/journal/account-inactive`, `/journal/account-not-a-leaf`,
`/period/not-sequentially-closable`, `/period/not-most-recently-closed`,
`/admin/cannot-orphan-self`, `/admin/tenant-deactivated`,
`/account/code-already-exists`, `/account/cycle-would-be-created`,
`/account/code-in-use-by-posting`.

Two of these are worth knowing. `/journal/account-not-a-leaf` is `409` because
the account's children can be re-parented, after which the same posting
succeeds; `/journal/account-currency-mismatch` stays `400` because an account's
currency is fixed. `/admin/tenant-deactivated` is mapped but no endpoint
returns it today (`DELETE /admin/tenants/{id}` answers `200` for an
already-deactivated tenant), so it changes nothing on the wire yet.

---

## Journal entry errors

Returned by `POST /journal-entries`, `POST /journal-entries/{id}/reverse`,
`GET /journal-entries/{id}` and `GET /journal-entries`.

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/journal/no-postings` | Journal entry has no postings | 400 | The entry had an empty `postings` array. | Include at least one posting. (A well-formed double entry has at least two.) |
| `/journal/unbalanced` | Journal entry is not balanced | 400 | Sum of debits ≠ sum of credits, measured in the base currency. | Adjust amounts so total debits equal total credits. |
| `/journal/mixed-currencies` | Journal entry mixes currencies | 400 | Postings reference more than one transaction currency in a slice that requires a single one. | Split into one entry per transaction currency, or use base-anchored multi-currency postings. |
| `/journal/account-not-found` | Posting references an unknown account | 400 | A posting's `account` code does not exist in the chart of accounts. (A name in the body, so `400`, not `404`.) | Create the account first (`POST /accounts`) or fix the code. |
| `/journal/account-inactive` | Posting references a deactivated account | 409 | The target account exists but has been deactivated. | Post to an active account, or reactivate it (`POST /accounts/{code}/reactivate`) and retry. |
| `/journal/account-not-a-leaf` | Posting targets a non-leaf account | 409 | The account has child accounts; only leaf accounts can be posted to. | Post to one of its leaf descendants instead, or re-parent its children and retry. |
| `/journal/account-currency-mismatch` | Posting currency does not match account currency | 400 | A posting's `currency` differs from the account's fixed currency. | Use the account's own currency, or post to an account denominated in the currency you need. |
| `/journal/posting-in-closed-period` | Posting falls in a closed period | 409 | `occurredOn` lands in a calendar month that has been closed. Also returned by the reverse endpoint when today's month is closed. | Reopen the period (`POST /periods/{yyyymm}/reopen`) and retry, or date the entry into an open period. |
| `/journal/base-currency-mismatch` | Posting baseAmount currency does not match the configured base | 400 | A posting's `baseMinorUnits` were interpreted in a currency other than the server's configured base. | Express `baseMinorUnits` in the configured base currency (default `USD`). |
| `/journal/overflow` | Posting sum overflowed | 400 | Summing one side of the entry exceeded `Long.MAX_VALUE` minor units. | Split the entry into smaller amounts. (This is a guardrail against absurd inputs.) |
| `/journal/already-reversed` | Journal entry has already been reversed | 409 | `POST /journal-entries/{id}/reverse` on an entry that already has a reversal. | None needed: the entry is already reversed. |
| `/journal/not-found` | Journal entry not found | 404 | The `{id}` in the URL is not a journal entry in your tenant (including a value that is not a UUID). | Check the id. |
| `/journal/invalid-query` | Invalid journal-entry query | 400 | A `GET /journal-entries` query parameter is malformed (for example a blank `account` or a malformed `after` cursor). | Fix the parameter named in `detail`. |

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

`/journal/posting-in-closed-period`

```json
{
  "type": "https://embracejoy.co/problems/journal/posting-in-closed-period",
  "title": "Posting falls in a closed period",
  "status": 409,
  "detail": "Period 2026-05 is closed; reopen it before posting.",
  "instance": "/journal-entries"
}
```

---

## Account errors

Returned by the `/accounts` endpoints (`POST /accounts`, `PATCH /accounts/{code}`,
the (de)activation endpoints).

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/account/code-already-exists` | Account code already exists | 409 | Creating an account with a code already in the chart. | Choose a different code, or update the existing account. |
| `/account/not-found` | Account not found | 404 | Referenced a code that isn't in the chart. | Create the account, or correct the code. |
| `/account/parent-not-found` | Parent account not found | 400 | `parentCode` points at an account that doesn't exist. | Create the parent first, or drop/fix `parentCode`. |
| `/account/self-parent` | Account cannot be its own parent | 400 | `PATCH /accounts/{code}` set `newParentCode` to the account's own code. No change to the chart can make this succeed, so it is invalid input, not a state conflict. | Choose a different parent, or send an empty `newParentCode` to detach. |
| `/account/cycle-would-be-created` | Account hierarchy would form a cycle | 409 | Re-parenting would make an account its own ancestor through *other* accounts (the proposed parent is a descendant). It could succeed after the tree changes. | Choose a parent that isn't one of the account's descendants, or re-parent the descendant first. |
| `/account/code-in-use-by-posting` | Account code already in use | 409 | `PATCH /accounts/{code}` renamed the account to a code another account already holds. | Pick a different code, or free the code first. |

> **Change note, 2026-10-12 (additive).** `/account/self-parent` is new (issue #92).
> Before it, re-parenting an account under itself returned
> `/account/cycle-would-be-created` with `409`; that case now returns
> `/account/self-parent` with `400`, per rule 2 of
> [ADR-0024](adr/0024-http-status-policy-for-domain-errors.md). The status of
> `/account/cycle-would-be-created` is unchanged (`409`, real cycles only), and
> no existing `type` was renamed or re-statused. Clients that branched on the
> old `type` for the self-parent request should also handle the new one.

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
| `/period/not-sequentially-closable` | Period close is out of order | 409 | Tried to close a month while an earlier month with postings is still open. | Close the earliest open active month first, then work forward. |
| `/period/not-most-recently-closed` | Period reopen requires the most-recently-closed period | 409 | Tried to reopen a month that isn't the latest closed one. | Reopen the most-recently-closed month first. |
| `/period/not-found` | Period not found | 404 | Looked up a period row that doesn't exist. | Most months are implicitly `OPEN` and have no row — a 404 here means "open, never closed". |

### Example body

`/period/not-sequentially-closable`

```json
{
  "type": "https://embracejoy.co/problems/period/not-sequentially-closable",
  "title": "Period close is out of order",
  "status": 409,
  "detail": "Cannot close 2026-03; close 2026-01 (or an earlier month) first.",
  "instance": "/periods/2026-03/close"
}
```

---

## Admin errors

Returned by the tenant endpoints (`/admin/tenants`) and the user-role endpoints
(`/admin/users`).

| `type` (relative to the base) | title | status | cause | resolution |
|---|---|---|---|---|
| `/admin/tenant-not-found` | Tenant not found | 404 | The tenant `{id}` in the URL does not exist (including a value that is not a UUID). | Check the id. |
| `/admin/tenant-invalid-name` | Tenant name is invalid | 400 | `POST /admin/tenants` was given a blank name. | Supply a non-blank name. |
| `/admin/tenant-deactivated` | Tenant is deactivated | 409 | An operation needed an active tenant. No endpoint returns this today. | Reactivation is not offered; use another tenant. |
| `/admin/user-role-not-found` | User role not found | 404 | No role assignment exists for `{userSub}` in your tenant. | Check the user id. |
| `/admin/cannot-orphan-self` | Cannot orphan the lone tenant admin | 409 | `PUT` or `DELETE /admin/users/{userSub}` would demote or remove the tenant's only Admin. | Grant Admin to another user first, then retry. |

---

## Authentication and authorisation errors

Returned by any endpoint. Unlike the problem types above, these are produced by
the security layer and currently carry a *relative* `type` reference
(`/problems/auth/...`) rather than a URI under the base.

| `type` | title | status | cause | resolution |
|---|---|---|---|---|
| `/problems/auth/unauthenticated` | Unauthorized | 401 | No valid credential. | Send a valid bearer token. |
| `/problems/auth/unknown-tenant` | Forbidden | 403 | The token's tenant claim names a tenant that does not exist. | Use a token for an existing tenant. |
| `/problems/auth/insufficient-role` | Forbidden | 403 | Your role does not allow this endpoint. | Ask a tenant Admin for the role the endpoint needs. |

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
