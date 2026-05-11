# ADR-0009: Trunk-based development with squash-merged PRs and required CI

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

We need a development model that:

- Keeps `main` always-shippable (CI green, every commit a single coherent
  change).
- Makes review explicit (no direct commits to `main`).
- Keeps `git log` readable (no merge bubbles, no per-commit churn).
- Provides a clean audit trail for every change.

Plan 1 + Plan 2 already implicitly followed this — every phase landed as
a separate PR with a squash merge. This ADR codifies the practice.

## Decision

- **Trunk-based:** `main` is the only long-lived branch. Feature work
  lives on short-lived branches named `<issue-number>-<slug>` (single
  issue) or `plan-N-phase-X-<slug>` (multi-task plan phase).
- **PR required:** no direct pushes to `main`. The Repository Ruleset in
  Phase C enforces this.
- **Required check:** the `build` job in `.github/workflows/ci.yml` must
  pass on every PR before merge. The check runs `./mvnw -B verify
  -Pmutation,openapi-gate` — every quality gate green every time.
- **Squash-merge only:** the PR becomes one commit on `main` with the
  PR's title as the commit subject and the body as the message. Merge
  commits and rebase merges are disabled.
- **Linear history:** force-pushes to `main` and merge commits on `main`
  are forbidden by the ruleset.
- **Signed commits:** required by the ruleset. The developer has SSH
  signing (`ed25519`) configured locally and the key registered on
  GitHub. Every new commit on every PR must be signed.

## Consequences

- A PR is the only path to `main` — easier auditing, harder mistakes.
- Every commit on `main` corresponds to a PR with a stable URL, a CI
  run, and a reviewable diff.
- We accept that no-CI commits (one-off doc tweaks) still need a PR;
  the friction is the point.
- `gh repo` and `gh pr` are part of the daily tool set; CONTRIBUTING.md
  documents the workflow.
- Reverting a change is a revert PR, never a force-push.
