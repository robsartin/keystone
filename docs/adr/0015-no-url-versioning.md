# ADR-0015: No URL versioning

Status: Accepted (2026-05-13)

## Context

Slice 5 makes every endpoint security-gated, which is a breaking change to the wire contract. With breaking changes on the table, the question naturally arises: should we add `/v1/` prefixes to URLs to allow side-by-side coexistence of old and new shapes?

The argument for URL versioning is "clients can opt in." The arguments against:

- URL versioning permanently forks every endpoint. Maintaining `/v1/accounts` and `/v2/accounts` doubles the controller surface, the OpenAPI spec, the integration tests, and the documentation.
- It creates an ambiguous source of truth: "what's the current version?" becomes a perennial question.
- Real version-skew problems are usually solved by feature flags or response shape evolution (additive fields), not by parallel URL trees.
- Industry practice has moved away from URL versioning for newer APIs (Stripe, Slack, GitHub all use header-based or implicit versioning today).

## Decision

Keystone APIs are versionless at the URL level. There will never be `/v1/...` or `/v2/...` paths.

Breaking changes are caught at PR time by the four-layer OpenAPI gate (Spectral lint + snapshot diff + openapi-diff vs `main` + the `breaking-change-approved` label flow established in [ADR-0006](0006-openapi-gates.md) and exercised in Slice 6 Phase C). When unavoidable, breaking changes are announced in release notes and an ADR.

If a versioned compatibility window ever becomes necessary later, it will be header-based (`API-Version: YYYY-MM-DD`, Stripe-style) — never URL-prefixed.

## Consequences

- **Positive**: One canonical URL per resource; no doubled controllers; no ambiguity.
- **Positive**: The `breaking-change-approved` PR label is the explicit, reviewed gate for any wire-contract change.
- **Negative**: Operators must coordinate client upgrades with server upgrades. There's no "leave the old version running for a quarter" escape hatch.
- **Mitigation**: Deprecation warnings via response headers (`Deprecation`, `Sunset` per RFC 8594) for known-breaking transitions, with at least one minor release of warning time before the breaking version ships.
