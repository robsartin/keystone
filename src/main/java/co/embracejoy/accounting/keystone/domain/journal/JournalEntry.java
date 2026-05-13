package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.money.MoneyError;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A balanced double-entry journal entry.
 *
 * <p>Construct via {@link #of(LocalDate, String, List)} or {@link #of(LocalDate, String, List,
 * JournalValidationContext)}; the factory enforces the invariants and returns a {@code Result}.
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

  /**
   * Build a journal entry with account validation. Validates: non-empty → single-currency →
   * per-posting account checks (exists → active → leaf → currency match) → no overflow → balanced.
   *
   * <p>Use {@link JournalValidationContext#permissive()} to skip account checks (backward compat).
   */
  public static Result<JournalEntry, JournalError> of(
      LocalDate occurredOn,
      String description,
      List<Posting> postings,
      JournalValidationContext ctx) {
    Objects.requireNonNull(occurredOn, "occurredOn");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(postings, "postings");
    Objects.requireNonNull(ctx, "ctx");

    if (postings.isEmpty()) {
      return Result.failure(new JournalError.NoPostings());
    }
    Set<Currency> currencies =
        postings.stream().map(p -> p.amount().currency()).collect(Collectors.toUnmodifiableSet());
    if (currencies.size() > 1) {
      return Result.failure(new JournalError.MixedCurrencies(currencies));
    }
    Currency currency = currencies.iterator().next();
    // Period check — must precede the per-posting account loop.
    if (!ctx.permissiveMode() && ctx.periodStatus() == PeriodStatus.CLOSED) {
      return Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.from(occurredOn)));
    }
    if (!ctx.permissiveMode()) {
      Result<JournalEntry, JournalError> err = checkAccounts(postings, ctx);
      if (err != null) {
        return err;
      }
    }
    return checkBalance(occurredOn, description, currency, postings);
  }

  /**
   * Backward-compatible overload: delegates with a permissive context (no account checks). Existing
   * callers keep compiling; new production callers use the four-arg form.
   */
  public static Result<JournalEntry, JournalError> of(
      LocalDate occurredOn, String description, List<Posting> postings) {
    return of(occurredOn, description, postings, JournalValidationContext.permissive());
  }

  private static Result<JournalEntry, JournalError> checkAccounts(
      List<Posting> postings, JournalValidationContext ctx) {
    for (Posting p : postings) {
      AccountCode code = p.account();
      Account account = ctx.accounts().get(code);
      if (account == null) {
        return Result.failure(new JournalError.AccountNotFound(code));
      }
      if (!account.active()) {
        return Result.failure(new JournalError.AccountInactive(code));
      }
      if (ctx.nonLeafCodes().contains(code)) {
        return Result.failure(new JournalError.AccountNotALeaf(code));
      }
      if (!account.currency().equals(p.amount().currency())) {
        return Result.failure(
            new JournalError.AccountCurrencyMismatch(
                code, account.currency(), p.amount().currency()));
      }
    }
    return null;
  }

  private static Result<JournalEntry, JournalError> checkBalance(
      LocalDate occurredOn, String description, Currency currency, List<Posting> postings) {
    Money zero = new Money(0L, currency);
    return sum(postings, Side.DEBIT, zero)
        .flatMap(
            debits ->
                sum(postings, Side.CREDIT, zero)
                    .flatMap(
                        credits -> {
                          if (debits.minorUnits() != credits.minorUnits()) {
                            return Result.failure(new JournalError.Unbalanced(debits, credits));
                          }
                          return Result.success(
                              new JournalEntry(occurredOn, description, currency, postings));
                        }));
  }

  private static Result<Money, JournalError> sum(List<Posting> postings, Side side, Money zero) {
    Money acc = zero;
    for (Posting p : postings) {
      if (p.side() == side) {
        Result<Money, MoneyError> next = acc.plus(p.amount());
        if (next instanceof Result.Success<Money, MoneyError> s) {
          acc = s.value();
        } else {
          return Result.failure(new JournalError.Overflow(side));
        }
      }
    }
    return Result.success(acc);
  }
}
