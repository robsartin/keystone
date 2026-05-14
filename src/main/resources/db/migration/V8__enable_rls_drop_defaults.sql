-- V8: Enable Postgres Row-Level Security on existing business tables; drop V7 DEFAULTs.
--
-- Part of Slice 5 Phase B-aggregates. The application now stamps tenant_id
-- on every INSERT (via the tenant-aware adapters in Account, Period,
-- JournalEntry repository adapters), so the V7 transitional DEFAULT clauses
-- are no longer needed. RLS is now safe to enable — every adapter calls
-- RlsTransactionInterceptor.applyToCurrentTransaction() to set
-- app.current_tenant before any query.
--
-- See ADR-0016 for the row-level isolation design.

-- ---------------------------------------------------------------------------
-- 1. Drop the V7 transitional DEFAULTs
-- ---------------------------------------------------------------------------

ALTER TABLE accounts        ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE journal_entries ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE postings        ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE periods         ALTER COLUMN tenant_id DROP DEFAULT;

-- ---------------------------------------------------------------------------
-- 2. Enable Row-Level Security
--
-- Each table's policy uses USING + WITH CHECK on
--   tenant_id = current_setting('app.current_tenant', true)::uuid
-- The 2nd arg to current_setting() is missing_ok=true, so an unset GUC
-- returns NULL — and tenant_id = NULL::uuid is false, so an unset GUC
-- yields zero rows. Defensive default: fail-closed.
-- ---------------------------------------------------------------------------

ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY accounts_tenant_isolation ON accounts
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY journal_entries_tenant_isolation ON journal_entries
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY postings_tenant_isolation ON postings
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE periods ENABLE ROW LEVEL SECURITY;
CREATE POLICY periods_tenant_isolation ON periods
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);
