package co.embracejoy.accounting.keystone.domain.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrialBalanceRow")
class TrialBalanceRowTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");

  @Test
  @DisplayName("balance() returns debits - credits; baseBalance() returns baseDebits - baseCredits")
  void shouldComputeBalanceFromDebitsMinusCredits() {
    // Asymmetric values catch a hypothetical copy-paste where baseBalance()
    // accidentally subtracts (debits - credits) instead of (baseDebits - baseCredits).
    TrialBalanceRow row = new TrialBalanceRow(CASH, USD, 9200L, 3200L, 15000L, 3000L);
    assertEquals(6000L, row.balance());
    assertEquals(12000L, row.baseBalance());
  }

  @Test
  @DisplayName("baseBalance() and balance() can be negative (credit-heavy)")
  void shouldReturnNegativeBalanceWhenCreditsExceedDebits() {
    TrialBalanceRow row = new TrialBalanceRow(CASH, USD, 0L, 5000L, 0L, 5000L);
    assertEquals(-5000L, row.balance());
    assertEquals(-5000L, row.baseBalance());
  }

  @Test
  @DisplayName("rejects null accountCode")
  void shouldThrowWhenAccountCodeIsNull() {
    assertThrows(NullPointerException.class, () -> new TrialBalanceRow(null, USD, 0L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(NullPointerException.class, () -> new TrialBalanceRow(CASH, null, 0L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative debits")
  void shouldThrowWhenDebitsAreNegative() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrialBalanceRow(CASH, USD, -1L, 0L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative credits")
  void shouldThrowWhenCreditsAreNegative() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrialBalanceRow(CASH, USD, 0L, -1L, 0L, 0L));
  }

  @Test
  @DisplayName("rejects negative baseDebits")
  void shouldThrowWhenBaseDebitsAreNegative() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrialBalanceRow(CASH, USD, 0L, 0L, -1L, 0L));
  }

  @Test
  @DisplayName("rejects negative baseCredits")
  void shouldThrowWhenBaseCreditsAreNegative() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrialBalanceRow(CASH, USD, 0L, 0L, 0L, -1L));
  }

  @Test
  @DisplayName("balance() handles maximum debit accumulators without overflow")
  void shouldComputeBalanceAtMaxLongDebits() {
    // With non-negative invariants, debits - credits ∈ [-Long.MAX_VALUE, Long.MAX_VALUE],
    // so subtractExact can never throw here. We still cover the boundary as a regression.
    TrialBalanceRow allDebits = new TrialBalanceRow(CASH, USD, Long.MAX_VALUE, 0L, 0L, 0L);
    TrialBalanceRow allCredits = new TrialBalanceRow(CASH, USD, 0L, Long.MAX_VALUE, 0L, 0L);
    assertEquals(Long.MAX_VALUE, allDebits.balance());
    assertEquals(-Long.MAX_VALUE, allCredits.balance());
  }
}
