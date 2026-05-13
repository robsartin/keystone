package co.embracejoy.accounting.keystone.domain.reports;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Currency;
import java.util.Objects;

/**
 * One row of a trial-balance projection: a single (account, transaction-currency) pair with
 * accumulated debits, credits, and the same in base currency.
 *
 * <p>This is a read model, not an aggregate. {@code balance()} and {@code baseBalance()} are
 * derived. All amount fields are non-negative integer minor units (see ADR-0003); the SQL adapter
 * uses {@code SUM(CASE WHEN side = ... THEN amount ELSE 0 END)} so each accumulator is a
 * non-negative running total.
 */
public record TrialBalanceRow(
    AccountCode accountCode,
    Currency currency,
    long debits,
    long credits,
    long baseDebits,
    long baseCredits) {

  public TrialBalanceRow {
    Objects.requireNonNull(accountCode, "accountCode");
    Objects.requireNonNull(currency, "currency");
    if (debits < 0L) {
      throw new IllegalArgumentException("debits must be non-negative; was " + debits);
    }
    if (credits < 0L) {
      throw new IllegalArgumentException("credits must be non-negative; was " + credits);
    }
    if (baseDebits < 0L) {
      throw new IllegalArgumentException("baseDebits must be non-negative; was " + baseDebits);
    }
    if (baseCredits < 0L) {
      throw new IllegalArgumentException("baseCredits must be non-negative; was " + baseCredits);
    }
  }

  /** Transaction-currency balance: {@code debits - credits}. */
  public long balance() {
    return Math.subtractExact(debits, credits);
  }

  /** Base-currency balance: {@code baseDebits - baseCredits}. */
  public long baseBalance() {
    return Math.subtractExact(baseDebits, baseCredits);
  }
}
