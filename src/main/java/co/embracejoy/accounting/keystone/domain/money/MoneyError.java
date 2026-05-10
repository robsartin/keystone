package co.embracejoy.accounting.keystone.domain.money;

import java.util.Currency;
import java.util.Objects;

/** Errors that can arise from {@link Money} arithmetic. */
public sealed interface MoneyError {

  /** Operands had different currencies. */
  record CurrencyMismatch(Currency expected, Currency actual) implements MoneyError {
    public CurrencyMismatch {
      Objects.requireNonNull(expected, "expected");
      Objects.requireNonNull(actual, "actual");
    }
  }

  /** Result would not fit in {@code long}. */
  record Overflow() implements MoneyError {}
}
