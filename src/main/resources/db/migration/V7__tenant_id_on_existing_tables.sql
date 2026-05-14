-- V7: Adds tenant_id to every existing business table.
--
-- Existing rows backfill to the Default Tenant (created by V6). The DEFAULT clause
-- on each new column lets existing application code keep inserting without specifying
-- tenant_id — those inserts get the default. The follow-up V8 migration enables RLS
-- and drops these DEFAULTs once the application stamps tenant_id explicitly.
--
-- Composite primary keys: account codes and period year-months are unique PER TENANT
-- (the same code can exist in two tenants). Foreign keys become composite to preserve
-- cross-tenant referential integrity.
--
-- See ADR-0016 for the row-level isolation design.

-- ---------------------------------------------------------------------------
-- 1. Add tenant_id columns with DEFAULT (existing rows auto-populate)
-- ---------------------------------------------------------------------------

ALTER TABLE accounts        ADD COLUMN tenant_id UUID NOT NULL
    DEFAULT '01902f9f-0000-7000-8000-00000000d1f1';
ALTER TABLE journal_entries ADD COLUMN tenant_id UUID NOT NULL
    DEFAULT '01902f9f-0000-7000-8000-00000000d1f1';
ALTER TABLE postings        ADD COLUMN tenant_id UUID NOT NULL
    DEFAULT '01902f9f-0000-7000-8000-00000000d1f1';
ALTER TABLE periods         ADD COLUMN tenant_id UUID NOT NULL
    DEFAULT '01902f9f-0000-7000-8000-00000000d1f1';

-- ---------------------------------------------------------------------------
-- 2. Foreign keys to tenants
-- ---------------------------------------------------------------------------

ALTER TABLE accounts        ADD CONSTRAINT fk_accounts_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE journal_entries ADD CONSTRAINT fk_journal_entries_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE postings        ADD CONSTRAINT fk_postings_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE periods         ADD CONSTRAINT fk_periods_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);

-- ---------------------------------------------------------------------------
-- 3. Composite primary keys (per-tenant uniqueness)
--
-- Order matters: drop dependent FKs first, then the old PK, then add the new
-- composite PK, then re-add the dependent FKs as composite.
--
-- accounts_pkey is referenced by:
--   • postings_account_code_fk  (postings.account_code → accounts.code)
--   • accounts_parent_code_fkey (accounts.parent_code → accounts.code, self-ref)
-- Both must be dropped before the PK can be replaced.
-- ---------------------------------------------------------------------------

-- Drop FKs that depend on the old accounts PK.
ALTER TABLE postings DROP CONSTRAINT postings_account_code_fk;
ALTER TABLE accounts DROP CONSTRAINT accounts_parent_code_fkey;

-- Replace accounts PK with composite.
ALTER TABLE accounts DROP CONSTRAINT accounts_pkey;
ALTER TABLE accounts ADD PRIMARY KEY (tenant_id, code);

-- Re-add postings → accounts FK as composite.
ALTER TABLE postings ADD CONSTRAINT postings_account_code_fk
    FOREIGN KEY (tenant_id, account_code) REFERENCES accounts(tenant_id, code);

-- Re-add self-referential parent FK as composite (parent must be in same tenant).
ALTER TABLE accounts ADD CONSTRAINT accounts_parent_code_fkey
    FOREIGN KEY (tenant_id, parent_code) REFERENCES accounts(tenant_id, code)
    ON UPDATE CASCADE;

-- Replace periods PK with composite.
ALTER TABLE periods DROP CONSTRAINT periods_pkey;
ALTER TABLE periods ADD PRIMARY KEY (tenant_id, year_month);

-- ---------------------------------------------------------------------------
-- 4. Indexes — tenant_id leads every plan
--
-- Note: the new composite primary keys auto-create an index on (tenant_id, code)
-- for accounts and (tenant_id, year_month) for periods, so we only add indexes
-- for the non-PK lookup paths.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_journal_entries_tenant      ON journal_entries (tenant_id, occurred_on);
CREATE INDEX idx_postings_tenant_account     ON postings        (tenant_id, account_code);
