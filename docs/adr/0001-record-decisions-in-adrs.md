# ADR-0001: Record significant decisions in ADRs

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

Significant architectural decisions accumulate over time. Without a paper
trail, future contributors (including future-me) re-litigate settled
choices, often without knowing the constraints that drove the original
decision.

## Decision

We use Architecture Decision Records, in Michael Nygard's format
(Context / Decision / Consequences), stored as Markdown files under
`docs/adr/`. Each ADR is numbered sequentially. ADRs are immutable once
Accepted — to change a decision, write a new ADR that supersedes the old
one, and update the old ADR's Status to "Superseded by ADR-XXXX".

ADRs are committed alongside the code that implements them, in the same
PR.

## Consequences

- Future contributors can quickly understand *why* the codebase is shaped
  the way it is.
- Reverting a decision requires writing the reasoning down, which raises
  the bar for casual changes to load-bearing choices.
- We accept the small overhead of writing ADRs for non-trivial decisions.
