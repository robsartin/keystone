# Keystone

A general ledger built in Spring Boot. This repository is the **keystone** — the
foundation that the rest of the ledger will grow from. See
[the foundation design spec](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
for the rationale and the full picture.

## Status

Plan 1 of 3 in progress: build skeleton + domain + application layer.

## Quick start (Plan 1)

```bash
./mvnw -B verify
```

Runs Spotless, Checkstyle, JUnit 6 unit tests, JaCoCo coverage check
(≥85% line), PIT mutation check (≥60% on domain + application), and
ArchUnit hexagonal rules.

## Architecture decisions

See [`docs/adr/`](docs/adr/) for the ADRs landed alongside the keystone.

## License

Apache 2.0 — see [LICENSE](LICENSE).
