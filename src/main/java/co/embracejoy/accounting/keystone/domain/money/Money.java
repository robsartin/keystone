package co.embracejoy.accounting.keystone.domain.money;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount represented as integer minor units of a known currency.
 *
 * <p>One {@code minorUnits} equals the smallest representable amount in the given currency: cents
 * for USD, yen for JPY, mils for BHD. See {@code currency.getDefaultFractionDigits()} for the
 * scale.
 */
public record Money(long minorUnits, Currency currency) {

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public boolean isZero() {
    return minorUnits == 0L;
  }

  public Money negate() {
    return new Money(-minorUnits, currency);
  }

  public Result<Money, MoneyError> plus(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      return Result.failure(new MoneyError.CurrencyMismatch(currency, other.currency));
    }
    try {
      return Result.success(new Money(Math.addExact(minorUnits, other.minorUnits), currency));
    } catch (ArithmeticException ignored) {
      return Result.failure(new MoneyError.Overflow());
    }
  }

  public Result<Money, MoneyError> minus(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      return Result.failure(new MoneyError.CurrencyMismatch(currency, other.currency));
    }
    try {
      return Result.success(new Money(Math.subtractExact(minorUnits, other.minorUnits), currency));
    } catch (ArithmeticException ignored) {
      return Result.failure(new MoneyError.Overflow());
    }
  }
}
