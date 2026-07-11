-- Slice 7 Phase A: journal-entry reversal metadata + posting audit columns
--
-- Nullable columns (or NOT NULL DEFAULT) so this migration is additive
-- (rolling-deploy safe). The composite FK preserves multi-tenant isolation:
-- a reversal in tenant A cannot point at an entry in tenant B, even
-- accidentally.
--
-- posted_at defaults to now() so existing rows backfill without a data
-- migration. posted_by is nullable — existing rows have no known actor;
-- new rows get it from the JWT sub.

ALTER TABLE journal_entries
  ADD COLUMN reverses_id UUID NULL,
  ADD COLUMN reversal_reason TEXT NULL,
  ADD COLUMN posted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN posted_by TEXT NULL;

-- journal_entries.id is already unique (it's the PK); this composite unique
-- constraint is what the composite FK below needs to reference. journal_entries
-- never got a composite (tenant_id, id) PK in V7 the way accounts/periods did,
-- since nothing needed to reference it by (tenant, id) until now.
ALTER TABLE journal_entries
  ADD CONSTRAINT journal_entries_tenant_id_unique UNIQUE (tenant_id, id);

ALTER TABLE journal_entries
  ADD CONSTRAINT journal_entries_reverses_fk
  FOREIGN KEY (tenant_id, reverses_id)
  REFERENCES journal_entries (tenant_id, id);

-- Index the reverse-lookup direction: "was this entry reversed?" and
-- "list this entry's reversal if any." Also covers the T5 findMany LEFT JOIN.
CREATE INDEX journal_entries_reverses_idx
  ON journal_entries (tenant_id, reverses_id);
