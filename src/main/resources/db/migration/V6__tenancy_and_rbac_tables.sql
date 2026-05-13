-- V6: Tenancy + RBAC tables (NEW TABLES ONLY).
--
-- Adds the tenants, tenant_user_roles, platform_admins tables and inserts the
-- default tenant. Enables Postgres Row-Level Security on tenant_user_roles only;
-- tenants and platform_admins are intentionally NOT RLS-protected (platform
-- admins need cross-tenant visibility for tenant CRUD).
--
-- Existing tables (accounts, journal_entries, postings, periods) are NOT
-- modified by this migration. The follow-up migration V7 (in the next PR,
-- "B-aggregates") adds tenant_id columns and RLS to those tables.
--
-- See ADR-0016 for the design rationale.

-- ---------------------------------------------------------------------------
-- New tables
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deactivated_at  TIMESTAMPTZ
);

CREATE TABLE tenant_user_roles (
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_sub     VARCHAR(255) NOT NULL,
    role         VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN', 'BOOKKEEPER', 'READ_ONLY')),
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (tenant_id, user_sub)
);
CREATE INDEX idx_tenant_user_roles_user ON tenant_user_roles (user_sub);

CREATE TABLE platform_admins (
    user_sub    VARCHAR(255) PRIMARY KEY,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Default tenant
-- ---------------------------------------------------------------------------

INSERT INTO tenants (id, name)
VALUES ('01902f9f-0000-7000-8000-00000000d1f1', 'Default Tenant');

-- ---------------------------------------------------------------------------
-- Row-Level Security on tenant_user_roles
-- ---------------------------------------------------------------------------
-- The app sets app.current_tenant per transaction; the policy filters rows
-- by it. The `true` 2nd arg to current_setting() returns NULL when unset;
-- tenant_id = NULL::uuid is false so an unset GUC means zero rows visible.

ALTER TABLE tenant_user_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_user_roles_tenant_isolation ON tenant_user_roles
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
