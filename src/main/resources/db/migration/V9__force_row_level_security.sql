-- V9: FORCE ROW LEVEL SECURITY on every RLS-protected table.
--
-- Postgres exempts a table's OWNER from RLS by default, even if the owner
-- role is NOSUPERUSER/NOBYPASSRLS. Migrations run as the DB owner (the
-- `keystone` role in docker-compose/production), so without FORCE the app
-- would silently bypass every policy just by virtue of connecting as the
-- owner.
--
-- FORCE ROW LEVEL SECURITY closes that owner-bypass. Combined with the
-- companion docker-compose init script `docker/postgres-init/01-nosuperuser.sql`
-- (which drops SUPERUSER from `keystone` before the app connects), this
-- makes the RLS policies from V6/V8 actually enforce cross-tenant isolation
-- in production and local dev.
--
-- Testcontainers instances continue to use their default superuser role,
-- so the RLS-agnostic ITs (AccountRepositoryAdapterIT etc.) keep working
-- via app-side filtering. RowLevelSecurityIT provisions its own restricted
-- role to exercise the RLS layer directly.
--
-- See ADR-0016 for the row-level isolation design.

ALTER TABLE accounts          FORCE ROW LEVEL SECURITY;
ALTER TABLE journal_entries   FORCE ROW LEVEL SECURITY;
ALTER TABLE postings          FORCE ROW LEVEL SECURITY;
ALTER TABLE periods           FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_user_roles FORCE ROW LEVEL SECURITY;
