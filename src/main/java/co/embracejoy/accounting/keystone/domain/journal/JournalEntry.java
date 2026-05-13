package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.money.MoneyError;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * A balanced double-entry journal entry.
 *
 * <p>Construct via {@link #of(LocalDate, String, List)} or {@link #of(LocalDate, String, List,
 * JournalValidationContext)}; the factory enforces the invariants and returns a {@code Result}.
 */
public record JournalEntry(LocalDate occurredOn, String description, List<Posting> postings) {

  public JournalEntry {
    Objects.requireNonNull(occurredOn, "occurredOn");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(postings, "postings");
    postings = List.copyOf(postings);
  }

  /**
   * Build a journal entry with account validation. Validates: non-empty → per-posting account
   * checks (exists → active → leaf → currency match) → no overflow → balanced in base currency.
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
    if (!ctx.permissiveMode() && ctx.periodStatus() == PeriodStatus.CLOSED) {
      return Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.from(occurredOn)));
    }
    Result<JournalEntry, JournalError> accountCheck = checkAccounts(postings, ctx);
    if (accountCheck != null) {
      return accountCheck;
    }
    // BaseCurrencyMismatch check: every posting's baseAmount must be in the configured base
    // currency. Permissive contexts (backward-compat 3-arg of() callers) skip this check.
    if (!ctx.permissiveMode()) {
      for (Posting p : postings) {
        if (!p.baseAmount().currency().equals(ctx.baseCurrency())) {
          return Result.failure(
              new JournalError.BaseCurrencyMismatch(
                  p.account(), ctx.baseCurrency(), p.baseAmount().currency()));
        }
      }
    }
    return checkBalance(occurredOn, description, postings, ctx);
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
        if (ctx.permissiveMode()) {
          continue;
        }
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

  // Phase A interim balance check: balance on baseAmount per the spec.
  // Phase B will add BaseCurrencyMismatch check before this step.
  private static Result<JournalEntry, JournalError> checkBalance(
      LocalDate occurredOn,
      String description,
      List<Posting> postings,
      JournalValidationContext ctx) {
    Money zero = new Money(0L, ctx.baseCurrency());
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
                              new JournalEntry(occurredOn, description, postings));
                        }));
  }

  // sum() sums baseAmount; the zero is in base currency.
  private static Result<Money, JournalError> sum(List<Posting> postings, Side side, Money zero) {
    Money acc = zero;
    for (Posting p : postings) {
      if (p.side() == side) {
        Result<Money, MoneyError> next = acc.plus(p.baseAmount());
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
