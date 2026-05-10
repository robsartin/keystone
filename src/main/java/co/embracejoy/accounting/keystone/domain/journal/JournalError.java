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
}
