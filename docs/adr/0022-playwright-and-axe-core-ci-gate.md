# ADR-0022: Playwright + axe-core browser tests as a CI gate

- **Status:** Accepted
- **Date:** 2026-07-08

## Context

The admin UI (Slice 5 Phase D, per [ADR-0021](0021-server-rendered-ui-thymeleaf-htmx-no-build.md))
is the first part of keystone with a human-facing surface. Accessibility
is a first-class requirement for anything a person operates directly,
not an afterthought bolted on after functional review.

The test suite so far covers rendering at the `MockMvc`/`@WebMvcTest`
level: controller returns the right view name, the right model
attributes, the right fragment on an HTMX swap. `MockMvc` never
constructs a DOM, never runs a real browser's layout or focus engine,
and never applies CSS — so it structurally cannot catch the class of
regression that matters most for accessibility: missing focus
indicators, keyboard traps, insufficient color contrast, missing/wrong
ARIA attributes as actually resolved by a browser, or a tab order that
doesn't match visual order. Those require a real rendering engine.

Given that accessibility is a first-class requirement, WCAG AA
violations need to fail the build the same way a failing unit test
does — surfacing as a build failure blocks the regression from landing
at all, versus surfacing as a review comment, which depends on a human
reviewer noticing and only after the change is already written.

## Decision

A `@SpringBootTest` (landing in T11 as `AdminUiE2ETest`) boots the full
application on a random port and drives a real, headless Chromium
instance via Playwright Java 1.49.0 against it — clicking through login,
navigating each admin page, and exercising the HTMX row-mutation flows
(role change, user removal, tenant deactivation) exactly as a person
would. On each page state reached, the test runs axe-core 4.10.2 against
the live DOM and asserts zero WCAG AA violations. This runs as part of
`./mvnw verify`, so it is a required CI gate, not an opt-in check.

Playwright was chosen over Selenium for auto-waiting (no manual
`Thread.sleep`/explicit-wait boilerplate for HTMX's async swaps) and
faster, more reliable browser automation in CI.

## Consequences

**Positive:**

- Accessibility regressions (missing labels, broken focus order, bad
  contrast, ARIA misuse) fail `./mvnw verify` the same way a broken
  domain invariant does — no separate a11y review pass required to catch
  them.
- Real-browser coverage catches a class of bug (CSS-driven, DOM-driven,
  focus-driven) that no `MockMvc` test — however thorough — can see.
- Playwright's auto-wait semantics mean the HTMX swap flows can be
  tested without brittle manual polling.

**Negative:**

- Real-browser tests are slower and heavier than `MockMvc` tests: a
  Chromium download/cache dependency, more CPU/memory in CI, and more
  moving parts to debug on flaky failures (timing, headless-rendering
  quirks) than a pure-JVM test.
- Playwright's Chromium binary must be available in the CI environment
  (installed once via Playwright's CLI/Maven hook); a CI image missing
  it fails the whole gate, not just the a11y assertions — an
  operational dependency this ADR accepts as the cost of real-browser
  coverage.
- axe-core catches automatable WCAG violations only; it is not a
  substitute for manual screen-reader/keyboard testing on features where
  automated coverage is known to be incomplete (e.g. genuinely correct
  reading order, meaningful alt text). It raises the floor, not the
  ceiling.

## Enforcement

Not code-structural in the ArchUnit sense — this is a runtime/behavioral
gate, not a bytecode-inspectable rule. Enforcement is the test itself:
`AdminUiE2ETest` (landing in T11) runs as part of `./mvnw verify`, and
its axe-core assertions *are* the enforcement mechanism — a WCAG AA
violation on any exercised page state fails that test, which fails the
build.
