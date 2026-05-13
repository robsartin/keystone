package co.embracejoy.accounting.keystone.domain.account;

/**
 * Whether an account currently accepts postings.
 *
 * <p>{@code ACTIVE} accounts may be posted to; {@code INACTIVE} accounts are rejected by {@code
 * JournalEntry.of(...)} with {@link
 * co.embracejoy.accounting.keystone.domain.journal.JournalError.AccountInactive}.
 */
public enum AccountStatus {
  ACTIVE,
  INACTIVE
}
