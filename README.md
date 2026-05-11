# Keystone

A general ledger built in Spring Boot. This repository is the **keystone** —
the foundation that the rest of the ledger grows from. See
[the foundation design spec](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
for the rationale and the full picture.

## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton (POST /journal-entries, JPA + Postgres + Flyway, observability, OpenAPI gates)
- [x] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning

## Quick start

```bash
docker compose up -d --build
```

Brings up Postgres, the app, Prometheus, and Grafana. Wait ~30 seconds for the app to boot, then:

```bash
curl -i -X POST http://localhost:8080/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "occurredOn": "2026-05-11",
    "description": "opening balance",
    "currency": "USD",
    "postings": [
      { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
      { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
    ]
  }'
```

Open the Grafana dashboard at [http://localhost:3000/d/keystone-overview](http://localhost:3000/d/keystone-overview). To shut down:

```bash
docker compose down       # keep volumes
docker compose down -v    # nuke volumes too
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

For a developer's tour of the layers with code walk-throughs, see
[`docs/development/architecture.md`](docs/development/architecture.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
