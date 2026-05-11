# ADR-0005: Postgres + Flyway from day one

- **Status:** Accepted
- **Date:** 2026-05-10

## Context

A general ledger lives or dies on its persistence and transaction story.
Embedded H2 lies about Postgres-specific behavior (UUID type, JSONB,
window functions, partial indexes) and would force a painful migration
later. Per the foundation design spec §3, the keystone uses real
Postgres from day one.

We need:

- Versioned migrations runnable from CI and in production
- Local dev convenience (one container, no manual schema management)
- Integration tests that exercise the same DDL as production

## Decision

- **Postgres 16** as the only supported database.
- **Flyway** for schema migrations, with versioned files in
  `src/main/resources/db/migration/V<N>__<slug>.sql`. `baseline-on-migrate=false`
  so a malformed initial state fails loud.
- **`hibernate.ddl-auto=validate`** — Hibernate validates that the JPA
  entities match the live schema; it never creates or alters tables.
  All DDL goes through Flyway.
- **Testcontainers** in tests (`org.testcontainers:postgresql` +
  `spring-boot-testcontainers`). Tests targeting the persistence
  adapter run against a real Postgres 16 container, not a mock or
  embedded DB.
- **UUID** column type for the journal entry primary key; matches
  ADR-0010's UUID v7 ids natively.

## Consequences

- Local dev requires a running Postgres (Plan 3's `docker-compose` makes
  this one command; before that, manual `docker run`).
- CI requires Docker available for Testcontainers.
- Schema changes go through PR review (the migration file diff is
  visible).
- Hibernate-only schema generation is forbidden — anyone adding a JPA
  entity must add a corresponding Flyway migration.
- Multi-database support is explicitly NOT a goal. If we ever need to
  support another database, it's a new ADR.
