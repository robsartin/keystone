# ADR-0006: OpenAPI four-layer gate

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

REST APIs drift. Without an enforced contract, tomorrow's controller
edit is yesterday's silent breaking change. We want machine-checked
guarantees that:

1. The OpenAPI spec is well-formed.
2. The spec follows internal style rules (operationId, ProblemDetails
   on errors, etc.).
3. Any change to the API surface is visible in the PR diff (no silent
   drift).
4. Breaking changes require explicit human acknowledgement.

## Decision

Four-layer gate, all run as part of `./mvnw verify`:

1. **Layer 1 — generation.** `springdoc-openapi-maven-plugin` boots the
   app and dumps `/v3/api-docs.yaml` to `target/openapi.yaml` during
   the `integration-test` phase. If a controller annotation is
   malformed the plugin fails.
2. **Layer 2 — lint.** Spectral (`@stoplight/spectral-cli` via npx)
   runs against `target/openapi.yaml` with rules in `.spectral.yaml`.
   Style violations fail the build.
3. **Layer 3 — snapshot diff.** `target/openapi.yaml` is diffed against
   the committed `docs/openapi/openapi.yaml`. Non-empty diff fails the
   build with a message instructing the developer to run
   `./mvnw -Popenapi-update verify` and commit the regenerated file.
4. **Layer 4 — breaking-change diff.** `openapi-diff-maven-plugin`
   compares `origin/main:docs/openapi/openapi.yaml` with the PR's
   committed snapshot. Breaking changes fail unless the PR carries
   the label `breaking-change-approved`. Skipped on push to main (no
   base ref). Bound to a Maven profile `openapi-gate` that CI activates;
   local inner loop can skip with `-P!openapi-gate`.

## Consequences

- Every API change is a two-file diff (controller + snapshot) that the
  reviewer sees together.
- Breaking changes require explicit reviewer attention via the label.
- Local builds stay fast; CI does the heavyweight gate.
- Spectral runs out-of-process via npx; CI must install Node. Local dev
  can skip Spectral via the profile if Node isn't present.
- `docs/openapi/openapi.yaml` is hand-maintained-via-regeneration; never
  hand-edited.
