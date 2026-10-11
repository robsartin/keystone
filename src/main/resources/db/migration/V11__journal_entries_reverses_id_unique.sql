-- Slice 7 follow-up (#81): back the "cannot reverse twice" invariant with the database.
--
-- ReverseJournalEntryService checks existsReversalOf across separate transactions, so two
-- near-simultaneous requests can both pass the check and both insert a reversal. This partial
-- unique index makes the second insert fail atomically. Partial (WHERE reverses_id IS NOT NULL)
-- so ordinary, non-reversal entries -- which have a NULL reverses_id -- are unconstrained.
--
-- Scoped by tenant_id to match the composite FK from V10. journal_entries_reverses_idx (V10) is
-- kept: it still serves the read model's LEFT JOIN for reversed_by.

CREATE UNIQUE INDEX journal_entries_reverses_id_unique
  ON journal_entries (tenant_id, reverses_id)
  WHERE reverses_id IS NOT NULL;
