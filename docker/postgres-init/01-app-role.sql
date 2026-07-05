-- Runs as POSTGRES_USER (=keystone) during Postgres init, BEFORE Flyway
-- migrations or the app connect. Provisions a separate NOSUPERUSER
-- application role so Row-Level Security actually applies at runtime.
--
-- Two roles after this script + migrations:
--   * keystone     — the built-in POSTGRES_USER. SUPERUSER, owns the database
--                    and all Flyway-migrated tables. Only used by Flyway
--                    migrations (for CREATE TABLE / CREATE POLICY / etc.).
--   * keystone_app — LOGIN NOSUPERUSER NOBYPASSRLS. This is what Spring's
--                    JPA `spring.datasource` connects as. It has CRUD on
--                    every table (via ALTER DEFAULT PRIVILEGES so Flyway's
--                    later CREATE TABLE grants come through) but no admin
--                    rights, so the RLS policies from V6/V8/V9 apply.
--
-- Combined with V9's `FORCE ROW LEVEL SECURITY`, this makes cross-tenant
-- reads and writes actually rejected at the database layer instead of
-- silently allowed by owner/superuser bypass.

CREATE ROLE keystone_app WITH LOGIN NOSUPERUSER NOBYPASSRLS
    PASSWORD 'keystone_app';

-- Basic connect + schema-use rights.
GRANT CONNECT ON DATABASE keystone TO keystone_app;
GRANT USAGE ON SCHEMA public TO keystone_app;

-- Future tables (created by Flyway as `keystone`) should grant CRUD to
-- keystone_app automatically. This runs FOR ROLE keystone so it applies
-- to objects `keystone` creates going forward.
ALTER DEFAULT PRIVILEGES FOR ROLE keystone IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO keystone_app;
ALTER DEFAULT PRIVILEGES FOR ROLE keystone IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO keystone_app;
