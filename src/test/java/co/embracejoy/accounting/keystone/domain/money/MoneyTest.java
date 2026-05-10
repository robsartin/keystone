package co.embracejoy.accounting.keystone.domain.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money")
class MoneyTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  @DisplayName("constructor rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(NullPointerException.class, () -> new Money(100L, null));
  }

  @Test
  @DisplayName("equality is value-based on amount and currency")
  void shouldBeEqualWhenAmountAndCurrencyMatch() {
    assertEquals(new Money(500L, USD), new Money(500L, USD));
  }

  @Test
  @DisplayName("isZero is true for zero amount")
  void shouldReturnTrueWhenAmountIsZero() {
    assertTrue(new Money(0L, USD).isZero());
  }

  @Test
  @DisplayName("isZero is false for non-zero amount")
  void shouldReturnFalseWhenAmountIsNonZero() {
    assertFalse(new Money(1L, USD).isZero());
  }

  @Test
  @DisplayName("negate() returns Success(-amount) for non-MIN values")
  void shouldFlipSignWhenNegateCalledOnNonMinValue() {
    Result<Money, MoneyError> r = new Money(100L, USD).negate();
    assertInstanceOf(Result.Success.class, r);
    assertEquals(new Money(-100L, USD), ((Result.Success<Money, MoneyError>) r).value());
  }

  @Test
  @DisplayName("negate() returns Success(0) when amount is zero")
  void shouldReturnZeroWhenNegateCalledOnZero() {
    Result<Money, MoneyError> r = new Money(0L, USD).negate();
    assertInstanceOf(Result.Success.class, r);
    assertEquals(new Money(0L, USD), ((Result.Success<Money, MoneyError>) r).value());
  }

  @Test
  @DisplayName("negate() returns Failure(Overflow) on Long.MIN_VALUE")
  void shouldReturnOverflowWhenNegateOnLongMinValue() {
    Result<Money, MoneyError> r = new Money(Long.MIN_VALUE, USD).negate();
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(MoneyError.Overflow.class, ((Result.Failure<Money, MoneyError>) r).error());
  }

  @Test
  @DisplayName("plus sums same-currency amounts")
  void shouldSumWhenPlusOnSameCurrency() {
    Result<Money, MoneyError> r = new Money(100L, USD).plus(new Money(250L, USD));
    assertInstanceOf(Result.Success.class, r);
    assertEquals(new Money(350L, USD), ((Result.Success<Money, MoneyError>) r).value());
  }

  @Test
  @DisplayName("plus fails on currency mismatch")
  void shouldReturnCurrencyMismatchWhenPlusOnDifferentCurrencies() {
    Result<Money, MoneyError> r = new Money(100L, USD).plus(new Money(100L, EUR));
    assertInstanceOf(Result.Failure.class, r);
    MoneyError e = ((Result.Failure<Money, MoneyError>) r).error();
    assertInstanceOf(MoneyError.CurrencyMismatch.class, e);
    MoneyError.CurrencyMismatch cm = (MoneyError.CurrencyMismatch) e;
    assertEquals(USD, cm.expected());
    assertEquals(EUR, cm.actual());
  }

  @Test
  @DisplayName("plus fails on overflow")
  void shouldReturnOverflowWhenPlusExceedsLongRange() {
    Result<Money, MoneyError> r = new Money(Long.MAX_VALUE, USD).plus(new Money(1L, USD));
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(MoneyError.Overflow.class, ((Result.Failure<Money, MoneyError>) r).error());
  }

  @Test
  @DisplayName("minus subtracts same-currency amounts")
  void shouldSubtractWhenMinusOnSameCurrency() {
    Result<Money, MoneyError> r = new Money(500L, USD).minus(new Money(200L, USD));
    assertInstanceOf(Result.Success.class, r);
    assertEquals(new Money(300L, USD), ((Result.Success<Money, MoneyError>) r).value());
  }

  @Test
  @DisplayName("minus fails on currency mismatch")
  void shouldReturnCurrencyMismatchWhenMinusOnDifferentCurrencies() {
    Result<Money, MoneyError> r = new Money(100L, USD).minus(new Money(50L, EUR));
    assertInstanceOf(Result.Failure.class, r);
    MoneyError e = ((Result.Failure<Money, MoneyError>) r).error();
    assertInstanceOf(MoneyError.CurrencyMismatch.class, e);
    MoneyError.CurrencyMismatch cm = (MoneyError.CurrencyMismatch) e;
    assertEquals(USD, cm.expected());
    assertEquals(EUR, cm.actual());
  }

  @Test
  @DisplayName("minus fails on overflow")
  void shouldReturnOverflowWhenMinusExceedsLongRange() {
    Result<Money, MoneyError> r = new Money(Long.MIN_VALUE, USD).minus(new Money(1L, USD));
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(MoneyError.Overflow.class, ((Result.Failure<Money, MoneyError>) r).error());
  }
}
