package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Objects;

/**
 * A single debit or credit against an account, with transaction-currency and base-currency amounts.
 *
 * <p>Sign is carried by {@link Side}; both {@link Money} amounts are non-negative. Zero is allowed
 * (memo postings). The {@code amount.currency()} is the transaction currency (must match the
 * account's currency, per Slice 2); {@code baseAmount.currency()} is the configured base currency
 * (validated in {@link JournalEntry#of(java.time.LocalDate, String, java.util.List,
 * JournalValidationContext)}).
 */
public record Posting(AccountCode account, Side side, Money amount, Money baseAmount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(baseAmount, "baseAmount");
    if (amount.minorUnits() < 0L) {
      throw new IllegalArgumentException("amount must be non-negative; sign is carried by Side");
    }
    if (baseAmount.minorUnits() < 0L) {
      throw new IllegalArgumentException("baseAmount must be non-negative");
    }
  }
}
