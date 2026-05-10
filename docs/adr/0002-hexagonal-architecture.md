# ADR-0002: Hexagonal architecture enforced by ArchUnit

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

A general ledger's correctness lives in the domain. Coupling that domain
to Spring, JPA, or HTTP concerns makes the rules harder to test, harder
to reason about, and harder to evolve. We want a structure where the
domain can be exercised entirely with plain JUnit, with no Spring context
and no database, and where adapter swaps (e.g. JPA → event-sourced) do
not touch domain code.

## Decision

We adopt **hexagonal architecture** (a.k.a. ports and adapters), with
three packages under `co.embracejoy.accounting.keystone`:

- `domain` — pure POJOs. Value objects, aggregates, domain errors, and
  **port interfaces**. May import only `java.*` and its own
  sub-packages. No Spring, no JPA, no Jackson, no SLF4J types as fields.
- `application` — use-case services that orchestrate the domain through
  ports. May depend only on `domain`.
- `infrastructure` — adapters. Implements ports (driven adapters: JPA,
  in-memory) and exposes the application via driving adapters (HTTP
  controllers). May depend on `domain` and `application`.

These rules are enforced at build time by an ArchUnit test
(`HexagonalArchitectureTest`).

### Worked example: why infrastructure imports domain

It is a common confusion that "infrastructure depends on domain" sounds
backwards. It is not. The Dependency Inversion Principle is about
**which side owns the abstraction**, not about which file imports
which.

In our codebase:

- `domain.journal.JournalEntryRepository` is an **interface** (a *port*)
  declared in `domain`. The domain owns the abstraction.
- `infrastructure.persistence.journal.JpaJournalEntryRepository` is a
  **class** that `implements JournalEntryRepository`. To do that, the
  Java source file has `import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;`
  at the top — a compile-time dependency on `domain`.

Both arrows point the same way: the *abstraction* arrow points inward
(domain owns it), and the *file-level import* arrow also points inward
(infrastructure depends on domain). What would be **wrong** is the
opposite: domain importing from infrastructure.

## Consequences

- Domain tests are fast and need no Spring context.
- Replacing JPA with another persistence story is an adapter change; the
  domain does not move.
- ArchUnit fails the build on any layering violation, so we do not have
  to police imports by hand.
- We accept the modest ceremony of writing ports + adapters even for
  simple use cases. The benefit pays back when the codebase grows.
