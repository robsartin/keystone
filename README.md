# Keystone

A general ledger built in Spring Boot. This repository is the **keystone** —
the foundation that the rest of the ledger grows from. See
[the foundation design spec](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
for the rationale and the full picture.

## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton (POST /journal-entries, JPA + Postgres + Flyway, observability, OpenAPI gates)
- [x] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning
- [x] Slice 2 — chart of accounts (#13)
- [x] Slice 3 — period model (#14)
- [x] Slice 4 — trial balance (#15)
- [x] Slice 5 — multi-tenant isolation, OAuth2 auth, RBAC, admin API + UI (#16)
- [x] Slice 6 — multi-currency journal entries (#17)

## Quick start

```bash
docker compose up -d --build
```

Brings up Postgres, the app, Prometheus, and Grafana. Wait ~30 seconds for the app to boot, then:

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

After posting some entries, get the trial balance:

```bash
curl -s 'http://localhost:8080/reports/trial-balance?asOf=2026-05-13' | jq
```

Open the Grafana dashboard at [http://localhost:3000/d/keystone-overview](http://localhost:3000/d/keystone-overview). To shut down:

```bash
docker compose down       # keep volumes
docker compose down -v    # nuke volumes too
```

## Documentation

- [Quick-start guide](docs/quick-start.md) — cold checkout to a posted entry, with request/response anatomy and failure walkthroughs.
- [Concepts](docs/concepts.md) — the model: journal entries, postings, money, accounts, periods.
- [API error reference](docs/errors.md) — every RFC 9457 problem `type` the API returns, with causes and fixes.
- [OpenAPI spec](docs/openapi/openapi.yaml) — the committed, CI-verified API contract (also live at `/v3/api-docs`).

## Build

```bash
./mvnw -B verify                  # fast local gate (no PIT, no OpenAPI lint)
./mvnw -B verify -Pmutation       # add PIT mutation coverage (≥60%)
./mvnw -B verify -Popenapi-gate   # add OpenAPI: Spectral lint + snapshot diff + openapi-diff
./mvnw -B verify -Popenapi-update # regenerate docs/openapi/openapi.yaml after an intentional API change
```

CI runs all profiles together: `./mvnw -B verify -Pmutation,openapi-gate`.

## Architecture decisions

See [`docs/adr/`](docs/adr/) — 22 ADRs covering hexagonal architecture,
integer money, Result pattern, JUnit 6, Postgres + Flyway, OpenAPI gates,
observability, typed IDs, multi-tenant row-level isolation, OAuth2 auth
(resource server + client), the embedded authorization server for dev/test,
the server-rendered admin UI, and Playwright + axe-core as a CI gate.

Per [ADR-0018](docs/adr/0018-archunit-enforce-adrs-where-possible.md),
every ADR whose rule admits automated enforcement ships with an
ArchUnit test. Currently enforced 🛡️: ADR-0002 (hexagonal layering),
0003 (money as integer), 0004 (Result over exceptions), 0008 (structured
logging), 0010 (typed IDs), 0015 (no URL versioning), 0019 (oauth2Login
only in UiSecurityConfig), 0020 (SAS on dev+test profile), 0021 (no
`package.json`).

For a developer's tour of the layers with code walk-throughs, see
[`docs/development/architecture.md`](docs/development/architecture.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
