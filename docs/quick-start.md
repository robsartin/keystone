# Quick-start guide

This walks you from a cold checkout to a posted journal entry in a couple of
minutes, then explains every field you sent and got back, shows what happens
when a request is rejected, and points you at the full API spec.

New to double-entry bookkeeping? The [concepts guide](concepts.md) explains the
model; this guide assumes only that you can run `curl`.

> **Authentication.** This slice runs unauthenticated: every request is
> automatically scoped to a single built-in tenant (Phase B). There is no API
> key or token to send yet. Row-level security and JWT-derived tenancy land in a
> later phase.

## 30-second hello world

**1. Start the stack** (Postgres, the app, Prometheus, Grafana) with one command:

```bash
docker compose up -d --build
```

Give the app ~30 seconds to boot, then confirm it's healthy:

```bash
curl -fsS http://localhost:8080/actuator/health
# {"status":"UP",...}
```

The chart of accounts is seeded with four accounts to post against: `1000` Cash,
`1100` Accounts Receivable, `3000` Owner Equity, and `4000` Revenue — all in USD.

**2. Post a balanced journal entry** — the owner puts $100.00 into the business:

```bash
curl -i -X POST http://localhost:8080/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "occurredOn": "2026-05-13",
    "description": "opening balance",
    "postings": [
      { "account": "1000", "side": "DEBIT",  "minorUnits": 10000,
        "currency": "USD", "baseMinorUnits": 10000 },
      { "account": "3000", "side": "CREDIT", "minorUnits": 10000,
        "currency": "USD", "baseMinorUnits": 10000 }
    ]
  }'
```

You get `201 Created`:

```http
HTTP/1.1 201
Location: /journal-entries/019f3d25-7583-7887-9b35-b385111f8092
X-Correlation-Id: 019f3d25-74f7-7655-b334-74d4c8c5d426
Content-Type: application/json

{"id":"019f3d25-7583-7887-9b35-b385111f8092","occurredOn":"2026-05-13","description":"opening balance","postings":[{"account":"1000","side":"DEBIT","minorUnits":10000,"currency":"USD","baseMinorUnits":10000},{"account":"3000","side":"CREDIT","minorUnits":10000,"currency":"USD","baseMinorUnits":10000}]}
```

**3. See the effect.** Journal entries are append-only and, in this slice, have
no read-by-id endpoint (the `Location` header names the resource for a future
`GET`). What you *can* do is read the **trial balance** — the running total per
account — which now reflects your entry:

```bash
curl -s http://localhost:8080/reports/trial-balance
```

```json
[
  { "accountCode": "1000", "currency": "USD", "debits": 10000, "credits": 0,
    "balance": 10000, "baseDebits": 10000, "baseCredits": 0, "baseBalance": 10000 },
  { "accountCode": "3000", "currency": "USD", "debits": 0, "credits": 10000,
    "balance": -10000, "baseDebits": 0, "baseCredits": 10000, "baseBalance": -10000 }
]
```

Cash is up $100.00 (a positive debit balance); Owner Equity shows the matching
$100.00 credit. The books balance. Tear everything down with
`docker compose down -v` when you're done.

## Anatomy of the request

```jsonc
{
  "occurredOn": "2026-05-13",   // required. Calendar date of the event (ISO-8601). Must fall in an open period.
  "description": "opening balance", // required, non-blank, ≤ 500 chars. Human summary.
  "postings": [                 // required, at least one. The debit and credit lines.
    {
      "account": "1000",        // required. An existing, active, leaf account code.
      "side": "DEBIT",          // required. Exactly "DEBIT" or "CREDIT".
      "minorUnits": 10000,      // required, ≥ 0. Amount in the transaction currency's minor units — 10000 = $100.00.
      "currency": "USD",        // required. 3-letter ISO 4217 code; must match the account's currency.
      "baseMinorUnits": 10000   // required, ≥ 0. Same amount in the server's base currency (default USD).
    }
    // ... at least one more posting so the entry balances
  ]
}
```

Key rules (all enforced server-side; see [error reference](errors.md)):

- **Money is integer minor units**, never a decimal — `10000` means `$100.00`.
  See [ADR-0003](adr/0003-money-as-integer-minor-units.md).
- **The entry must balance** — total debits must equal total credits, measured in
  the base currency (`baseMinorUnits`).
- **Each posting carries both amounts** — `minorUnits`/`currency` is the
  transaction amount; `baseMinorUnits` is that amount in the configured base
  currency. For a single-currency entry they're equal. See
  [ADR-0014](adr/0014-multi-currency-base-anchoring.md).

## Anatomy of the response

On success you get `201 Created` with:

| Part | Example | Meaning |
|---|---|---|
| `Location` header | `/journal-entries/019f3d25-…` | The URI of the newly created entry. Its last path segment is the entry's UUID (v7, time-ordered). |
| `X-Correlation-Id` header | `019f3d25-74f7-…` | The request's correlation ID, also stamped into the server logs — quote it in bug reports. Present on **every** response, success or error. |
| Body | see below | The persisted entry, echoed back with its assigned `id`. |

```json
{
  "id": "019f3d25-7583-7887-9b35-b385111f8092",
  "occurredOn": "2026-05-13",
  "description": "opening balance",
  "postings": [
    { "account": "1000", "side": "DEBIT",  "minorUnits": 10000, "currency": "USD", "baseMinorUnits": 10000 },
    { "account": "3000", "side": "CREDIT", "minorUnits": 10000, "currency": "USD", "baseMinorUnits": 10000 }
  ]
}
```

## When things go wrong

Every rejection is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
detail (`Content-Type: application/problem+json`). Branch your code on the
stable `type` URI; show the human `detail` to the user. The
[error reference](errors.md) documents every `type` the API can return — below
are three you'll hit early, each reproducible against the seed data above.

**1. Unbalanced** — debits ($100.00) don't equal credits ($90.00):

```bash
curl -s -X POST http://localhost:8080/journal-entries -H "Content-Type: application/json" -d '{
  "occurredOn": "2026-05-13", "description": "typo",
  "postings": [
    { "account": "1000", "side": "DEBIT",  "minorUnits": 10000, "currency": "USD", "baseMinorUnits": 10000 },
    { "account": "3000", "side": "CREDIT", "minorUnits":  9000, "currency": "USD", "baseMinorUnits":  9000 }
  ]
}'
```

```json
{
  "type": "https://embracejoy.co/problems/journal/unbalanced",
  "title": "Journal entry is not balanced",
  "status": 400,
  "detail": "Sum of debits (10000 USD) does not equal sum of credits (9000 USD).",
  "instance": "/journal-entries"
}
```

→ Make the two sides equal. See [`/journal/unbalanced`](errors.md#journal-entry-errors).

**2. Unknown account** — `9999` isn't in the chart:

```bash
curl -s -X POST http://localhost:8080/journal-entries -H "Content-Type: application/json" -d '{
  "occurredOn": "2026-05-13", "description": "ghost account",
  "postings": [
    { "account": "9999", "side": "DEBIT",  "minorUnits": 100, "currency": "USD", "baseMinorUnits": 100 },
    { "account": "3000", "side": "CREDIT", "minorUnits": 100, "currency": "USD", "baseMinorUnits": 100 }
  ]
}'
```

```json
{
  "type": "https://embracejoy.co/problems/journal/account-not-found",
  "title": "Posting references an unknown account",
  "status": 400,
  "detail": "Account code '9999' does not exist.",
  "instance": "/journal-entries"
}
```

→ Create the account (`POST /accounts`) or fix the code. See [`/journal/account-not-found`](errors.md#journal-entry-errors).

**3. Malformed request** — blank `description`, empty `postings`. This fails
*validation*, before the entry reaches the ledger, and reports every offending
field at once:

```bash
curl -s -X POST http://localhost:8080/journal-entries -H "Content-Type: application/json" -d '{
  "occurredOn": "2026-05-13", "description": "", "postings": []
}'
```

```json
{
  "type": "https://embracejoy.co/problems/validation",
  "title": "Request validation failed",
  "status": 400,
  "detail": "postings: postings must not be empty; description: description is required",
  "instance": "/journal-entries"
}
```

→ Fix each field named in `detail`. See [`/validation`](errors.md#validation-errors).

Other failures you might hit — mixing currencies, posting to a deactivated or
non-leaf account, posting into a closed period — are all in the
[error reference](errors.md).

## Where to find the API spec

- **Live, from the running app:** the OpenAPI document is served at
  [`http://localhost:8080/v3/api-docs`](http://localhost:8080/v3/api-docs)
  (JSON) and `http://localhost:8080/v3/api-docs.yaml` (YAML). Swagger UI is at
  `http://localhost:8080/swagger-ui.html`.
- **Committed snapshot:** [`docs/openapi/openapi.yaml`](openapi/openapi.yaml) is
  the checked-in spec. CI fails on any drift between it and the live app, so it's
  always current with `main` — see [ADR-0006](adr/0006-openapi-gates.md).

## Next steps

- [Concepts](concepts.md) — the model behind the API (journal entries, postings, money, periods).
- [Error reference](errors.md) — every problem `type` the API can return.
