package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import java.util.Set;

/** Reasons a {@link JournalEntry} factory may refuse to construct an entry. */
public sealed interface JournalError {

  /** No postings supplied. */
  record NoPostings() implements JournalError {}

  /** Postings reference more than one currency. */
  record MixedCurrencies(Set<Currency> currencies) implements JournalError {}

  /** Sum of debits does not equal sum of credits. */
  record Unbalanced(Money debits, Money credits) implements JournalError {}

  /** Same-side posting sum overflowed Long.MAX_VALUE. */
  record Overflow(Side side) implements JournalError {}

  /** A posting references an unknown account code. */
  record AccountNotFound(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** A posting references a deactivated account. */
  record AccountInactive(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** A posting targets an account that has child accounts (not a leaf). */
  record AccountNotALeaf(co.embracejoy.accounting.keystone.domain.account.AccountCode code)
      implements JournalError {}

  /** Posting amount's currency differs from the account's currency. */
  record AccountCurrencyMismatch(
      co.embracejoy.accounting.keystone.domain.account.AccountCode code,
      java.util.Currency expectedByAccount,
      java.util.Currency actualOnPosting)
      implements JournalError {}

  /** Entry's occurredOn falls in a YearMonth that has been closed. */
  record PostingInClosedPeriod(java.time.YearMonth period) implements JournalError {}

  /**
   * A posting's baseAmount currency differs from the configured base currency.
   *
   * @param code the offending account code
   * @param expectedByConfig the configured base from {@code keystone.base-currency}
   * @param actualOnPosting the currency the posting's baseAmount was sent with
   */
  record BaseCurrencyMismatch(
      co.embracejoy.accounting.keystone.domain.account.AccountCode code,
      java.util.Currency expectedByConfig,
      java.util.Currency actualOnPosting)
      implements JournalError {}

  /** No journal entry exists with the given id (for the given tenant). */
  record NotFound(JournalEntryId id) implements JournalError {}

  /** The requested original has already been reversed — cannot reverse twice. */
  record AlreadyReversed(JournalEntryId id) implements JournalError {}
}
