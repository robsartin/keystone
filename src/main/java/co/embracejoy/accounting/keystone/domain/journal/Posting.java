package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Objects;

/**
 * A single debit or credit against an account.
 *
 * <p>Sign is carried by {@link Side}; the {@link Money} amount is non-negative. Zero is allowed
 * (memo postings).
 */
public record Posting(AccountCode account, Side side, Money amount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() < 0L) {
      throw new IllegalArgumentException(
          "Posting amount must be non-negative; sign is carried by Side");
    }
  }
}
