package co.embracejoy.accounting.keystone.domain.journal;

/**
 * Validation mode for {@link JournalEntry#of(java.time.LocalDate, String, java.util.List,
 * JournalValidationContext)}.
 *
 * <p>{@code STRICT} runs every check (account existence/active/leaf/currency, period status, base
 * currency match). {@code PERMISSIVE} skips account-membership, period, and base-currency checks —
 * used by tests and the backward-compatible {@code of(occurredOn, description, postings)} overload
 * that doesn't take a context. Balance and overflow checks always run.
 */
public enum JournalValidationMode {
  STRICT,
  PERMISSIVE
}
