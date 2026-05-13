-- V5__postings_multi_currency.sql
-- Slice 6: per-posting currency + base-currency amount.

-- 1. Add currency column to postings (will hold the transaction currency).
ALTER TABLE postings ADD COLUMN currency VARCHAR(3);

-- 2. Backfill from the entry's currency.
UPDATE postings p
   SET currency = (SELECT currency FROM journal_entries WHERE id = p.journal_entry_id);

-- 3. Tighten to NOT NULL.
ALTER TABLE postings ALTER COLUMN currency SET NOT NULL;

-- 4. Add base_minor_units column.
ALTER TABLE postings ADD COLUMN base_minor_units BIGINT;

-- 5. Backfill: pre-Slice-6 was single-currency-USD, so base equals amount.
UPDATE postings SET base_minor_units = amount_minor_units;

-- 6. Tighten + non-negative check.
ALTER TABLE postings ALTER COLUMN base_minor_units SET NOT NULL;
ALTER TABLE postings
    ADD CONSTRAINT postings_base_minor_units_nonneg
    CHECK (base_minor_units >= 0);

-- 7. Drop the (now redundant) entry-level currency.
ALTER TABLE journal_entries DROP COLUMN currency;
