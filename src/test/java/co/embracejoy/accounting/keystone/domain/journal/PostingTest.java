package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Posting")
class PostingTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");

  @Test
  @DisplayName("rejects null account")
  void shouldThrowWhenAccountIsNull() {
    assertThrows(
        NullPointerException.class, () -> new Posting(null, Side.DEBIT, new Money(100L, USD)));
  }

  @Test
  @DisplayName("rejects null side")
  void shouldThrowWhenSideIsNull() {
    assertThrows(NullPointerException.class, () -> new Posting(CASH, null, new Money(100L, USD)));
  }

  @Test
  @DisplayName("rejects null amount")
  void shouldThrowWhenAmountIsNull() {
    assertThrows(NullPointerException.class, () -> new Posting(CASH, Side.DEBIT, null));
  }

  @Test
  @DisplayName("allows zero amount (memo posting)")
  void shouldAcceptWhenAmountIsZero() {
    Posting p = new Posting(CASH, Side.DEBIT, new Money(0L, USD));
    assertEquals(0L, p.amount().minorUnits());
  }

  @Test
  @DisplayName("rejects negative amount; sign is carried by Side")
  void shouldThrowWhenAmountIsNegative() {
    assertThrows(
        IllegalArgumentException.class, () -> new Posting(CASH, Side.DEBIT, new Money(-1L, USD)));
  }

  @Test
  @DisplayName("equality is value-based")
  void shouldBeEqualWhenAllComponentsMatch() {
    Posting a = new Posting(CASH, Side.DEBIT, new Money(100L, USD));
    Posting b = new Posting(CASH, Side.DEBIT, new Money(100L, USD));
    assertEquals(a, b);
  }
}
