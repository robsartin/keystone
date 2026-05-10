package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.money.MoneyError;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A balanced double-entry journal entry.
 *
 * <p>Construct via {@link #of(LocalDate, String, List)}; the factory enforces the invariants
 * (non-empty, single-currency, balanced) and returns a {@code Result} so callers handle failures
 * explicitly.
 */
public record JournalEntry(
    LocalDate occurredOn, String description, Currency currency, List<Posting> postings) {

  public JournalEntry {
    Objects.requireNonNull(occurredOn, "occurredOn");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(postings, "postings");
    postings = List.copyOf(postings);
  }

  public static Result<JournalEntry, JournalError> of(
      LocalDate occurredOn, String description, List<Posting> postings) {
    Objects.requireNonNull(occurredOn, "occurredOn");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(postings, "postings");

    if (postings.isEmpty()) {
      return Result.failure(new JournalError.NoPostings());
    }

    Set<Currency> currencies =
        postings.stream().map(p -> p.amount().currency()).collect(Collectors.toUnmodifiableSet());
    if (currencies.size() > 1) {
      return Result.failure(new JournalError.MixedCurrencies(currencies));
    }
    Currency currency = currencies.iterator().next();

    Money zero = new Money(0L, currency);
    Money debits = sum(postings, Side.DEBIT, zero);
    Money credits = sum(postings, Side.CREDIT, zero);

    if (debits.minorUnits() != credits.minorUnits()) {
      return Result.failure(new JournalError.Unbalanced(debits, credits));
    }

    return Result.success(new JournalEntry(occurredOn, description, currency, postings));
  }

  private static Money sum(List<Posting> postings, Side side, Money zero) {
    Money acc = zero;
    for (Posting p : postings) {
      if (p.side() == side) {
        Result<Money, MoneyError> next = acc.plus(p.amount());
        if (next instanceof Result.Success<Money, MoneyError> s) {
          acc = s.value();
        } else {
          throw new ArithmeticException("Posting sum overflowed " + side);
        }
      }
    }
    return acc;
  }
}
