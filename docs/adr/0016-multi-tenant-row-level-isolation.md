# ADR-0016: Multi-tenant row-level isolation with Postgres RLS

Status: Accepted (2026-05-13)

## Context

Slice 5 introduces multi-tenancy. The keystone serves multiple tenants from a single deployment (with a clean single-tenant standalone fallback). Three isolation strategies were considered:

1. **Schema-per-tenant**: each tenant gets its own Postgres schema. Per-tenant Flyway runs; per-tenant connection pool overhead. Doesn't scale past hundreds of tenants. Operational complexity (e.g., bulk migrations across N schemas).
2. **Database-per-tenant**: each tenant gets its own database. Strongest isolation; worst operational story for a single-deployment SaaS.
3. **Row-level (`tenant_id` column on every business table)**: simplest schema; scales to millions of tenants; risk is that a single forgotten `WHERE tenant_id = ?` leaks data across tenants.

For a financial ledger, cross-tenant data leakage is catastrophic — clients would never trust the system again. We want a defense-in-depth solution.

## Decision

Use row-level isolation with `tenant_id UUID NOT NULL` on every business table, **enforced by both layers**:

- **Application filter**: a request-scoped `TenantContext` bean is populated from the JWT custom claim. Repository adapters read it and apply `WHERE tenant_id = ?` to every query and validate it on every write.
- **Postgres Row-Level Security**: every tenant-scoped table has an RLS policy (`USING tenant_id = current_setting('app.current_tenant', true)::uuid`). A transaction interceptor sets the GUC at the start of each transaction. Policies use `WITH CHECK` so RLS rejects misrouted writes too.

For platform-admin operations that span tenants (tenant CRUD), a separate Postgres role `keystone_platform` with `BYPASSRLS` granted is used — connected via a second `DataSource` and a separate JPA `EntityManagerFactory`.

## Consequences

- **Positive**: Defense in depth. A bug in application filtering still can't leak data — RLS blocks it. A bug in RLS setup is caught by the application filter and the dedicated `RowLevelSecurityIT`.
- **Positive**: Account codes and period year-months become unique per tenant (composite primary keys), which is the natural mental model for accounting systems.
- **Negative**: Every existing repository, entity, mapper, and test fixture changes. ~80 sites updated.
- **Negative**: Two `DataSource`s and two `EntityManagerFactory`s — more boot config.
- **Mitigation**: ArchUnit rules enforce that every adapter reads `TenantContext` (or takes `TenantId` as a parameter). The centerpiece `RowLevelSecurityIT` exercises both layers in isolation and together.
