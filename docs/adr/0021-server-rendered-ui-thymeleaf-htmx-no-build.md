# ADR-0021: Server-rendered admin UI — Thymeleaf + HTMX, no JS build step

- **Status:** Accepted
- **Date:** 2026-07-08

## Context

Slice 5 Phase D adds a small admin UI: five pages (home, users, tenants,
profile, login) with a handful of in-place mutations (change a user's
role, remove a user, deactivate a tenant). None of it needs client-side
routing, component state management, or a rich interactive widget
library.

A conventional JS frontend (React/Vue + webpack/esbuild/Vite) would add:
a Node.js toolchain as a build-time dependency alongside the JVM/Maven
toolchain already in place; `package.json` + a lockfile to keep in sync
with `pom.xml`'s dependency management; a `node_modules` tree to
`.gitignore`, cache in CI, and periodically audit for supply-chain risk;
and a build step (`npm run build` or equivalent) that has to run before
`spring-boot:run` or the Docker image build can produce working static
assets — a second build pipeline for a UI this small.

## Decision

The admin UI is server-rendered with Thymeleaf, using HTMX 2.0.4 for the
handful of in-place mutations (role change, user removal, tenant
deactivation) that would otherwise need a full page reload, and
Bootstrap 5.3.3 CSS plus Bootstrap Icons for layout and styling. All
three are vendored as static files directly under
`src/main/resources/static/` (`htmx.min.js`, `bootstrap.min.css`,
`bootstrap-icons.css` + `.woff2`, plus keystone's own
`keystone-admin.css`) and served by Spring's default static-resource
handling — no `package.json`, no `node_modules`, no separate build
step. Page-to-page navigation is plain `<a href>`; HTMX's scope is
strictly in-place row mutations within a page (`hx-post`/`hx-target`
swaps against `fragments/*.html` Thymeleaf fragments), not client-side
routing.

## Consequences

**Positive:**

- One toolchain, one build (`./mvnw verify`/`spring-boot:run`), one
  Docker build stage. A contributor with only a JDK and Maven can build
  and run the entire application, UI included.
- No `npm install` supply-chain surface, no lockfile drift between two
  package ecosystems, no JS build-cache invalidation bugs.
- Server-rendered HTML with progressive HTMX enhancement is naturally
  more accessible by default (real page loads, real forms, no
  client-side router breaking browser back/forward or screen-reader
  landmarks) — reinforced by [ADR-0022](0022-playwright-and-axe-core-ci-gate.md)'s
  axe-core gate.

**Negative:**

- Vendored assets are updated by hand (download + replace the file),
  not by `npm update` + lockfile diff — upgrading HTMX or Bootstrap is a
  manual, deliberate step with no automated dependency-alert tooling
  (e.g. Dependabot won't see these as declared dependencies).
- If the admin UI's interactivity needs grow substantially (client-side
  state, complex forms, real-time updates beyond HTMX's swap model),
  this decision will need revisiting — HTMX intentionally does not scale
  to SPA-class interactivity, and that's an explicit non-goal here, not
  an oversight.

## Enforcement

Not code-structural — ArchUnit inspects compiled bytecode, not repo
topology, so it cannot see whether a `package.json` exists. Enforcement
is `NoBuildStepTest`
(`src/test/java/co/embracejoy/accounting/keystone/architecture/NoBuildStepTest.java`),
a plain JUnit `@Test` (not an `@ArchTest`) that asserts neither
`package.json` nor `node_modules` exists at the repository root. It
runs on every `./mvnw verify`, so a JS build step introduced by mistake
(or by a future contributor unaware of this ADR) fails the build
immediately rather than surviving to review.
