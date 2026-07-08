# ADR-0018: ArchUnit-enforce ADRs where the technology admits it

- **Status:** Accepted
- **Date:** 2026-07-07

## Context

The project already runs [ArchUnit](https://www.archunit.org/) at every
`./mvnw verify` (see [ADR-0002](0002-hexagonal-architecture.md) and
[HexagonalArchitectureTest](../../src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java)).
Fifteen rules cover the hexagonal layering, and one proxy rule
(`NO_PUBLIC_METHOD_RETURNS_THROWABLE`) partially guards
[ADR-0004](0004-result-type-and-problem-details.md).

Every other ADR is enforced only by convention — a reviewer's memory, a
comment in `CLAUDE.md`, or a note in the ADR itself. Conventions decay.
An ADR without executable enforcement is one refactor away from silently
being false.

Several existing ADRs describe rules that ArchUnit *could* express:

| ADR | Rule |
|---|---|
| [0003](0003-money-as-integer-minor-units.md) | No `double`/`float`/`BigDecimal` fields in `..domain..` |
| [0004](0004-result-type-and-problem-details.md) | Public methods in `..application..` don't declare `throws` |
| [0008](0008-observability.md) | No `System.out`/`System.err`/`printStackTrace` in production code |
| [0010](0010-journal-entry-id-wrapper.md) | Domain records don't hold raw `UUID` fields; use a typed ID wrapper |
| [0015](0015-no-url-versioning.md) | `@RequestMapping`-family annotation values don't match `/v[0-9]+/` |

Other ADRs describe runtime invariants (e.g., "posting is balanced"), DB
configuration ([ADR-0016](0016-multi-tenant-row-level-isolation.md)),
build-time gates ([ADR-0006](0006-openapi-gates.md)), or process
choices ([ADR-0009](0009-trunk-based-development.md)). ArchUnit can't
express those, and shouldn't try to.

## Decision

**Every new ADR that describes a code-structure rule ships with an
ArchUnit test that enforces it.** The rule and the ADR land in the same
PR when possible; when not, an issue with the "ready" label tracks the
gap.

Concretely:

1. When drafting a new ADR, we ask: *Can ArchUnit express this rule
   against production bytecode?* If yes, we add a rule.
2. The ADR gets an "Enforcement" section citing the test class + rule
   name (e.g., `HexagonalArchitectureTest.CONTROLLERS_LIVE_IN_WEB_PACKAGE`).
3. If the rule ships in a follow-up PR (rare — prefer same-PR), the ADR
   links to a ready-labelled GitHub issue instead.

**Existing uncovered ADRs get backfilled** per the checklist in issue
[#68](https://github.com/robsartin/keystone/issues/68): ADRs 0003, 0004
(strict), 0008, 0010, 0015.

**ADRs whose rule is not code-structural** (runtime invariants, DB
config, build-time gates, process) are exempt. Their enforcement lives
elsewhere — unit tests, Testcontainers, CI profiles, PR review — and the
ADR body says so explicitly.

## Consequences

**Easier:**

- Reviewers stop needing to remember every ADR when checking a PR — the
  build enforces them.
- Refactors that would silently violate an ADR fail loudly at
  `./mvnw verify` on the developer's laptop.
- Onboarding: new contributors see the rules in code, not just prose.

**Harder:**

- Each new ADR has a small extra step (write the rule).
- Refactors that intentionally change a rule now need two PRs of paperwork
  (change the rule, update the ADR body).
- Some ADRs will inspire creative "how do I express this in ArchUnit?"
  wrestling; complexity budget is real.

**Committing to:**

- Backfilling ADRs 0003, 0004, 0008, 0010, 0015 per issue
  [#68](https://github.com/robsartin/keystone/issues/68).
- Adding an "Enforcement" section to every future ADR — either citing a
  rule or explicitly stating the enforcement path (unit test,
  Testcontainers, CI profile, review).
- Keeping the ArchUnit test suite fast (`ImportOption.DoNotIncludeTests`
  where the rule is production-only, one focused test class per theme
  rather than one giant file).

## Enforcement

This ADR is a meta-rule about how we write ADRs. It has no ArchUnit test
of its own; enforcement is by review — any new ADR PR that omits an
"Enforcement" section (or a stated reason for the omission) should be
requested-changes.
