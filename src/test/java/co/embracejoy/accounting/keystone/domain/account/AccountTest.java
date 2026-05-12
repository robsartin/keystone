package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Account")
class AccountTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode ASSETS = new AccountCode("1");

  @Test
  @DisplayName("rejects null code")
  void shouldThrowWhenCodeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(null, "Cash", AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null name")
  void shouldThrowWhenNameIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, null, AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects blank name")
  void shouldThrowWhenNameIsBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Account(CASH, "  ", AccountType.ASSET, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null type")
  void shouldThrowWhenTypeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", null, USD, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, null, Optional.empty(), true));
  }

  @Test
  @DisplayName("rejects null parentCode Optional")
  void shouldThrowWhenParentOptionalIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, USD, null, true));
  }

  @Test
  @DisplayName("rejects self-parent")
  void shouldThrowWhenAccountIsItsOwnParent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.of(CASH), true));
  }

  @Test
  @DisplayName("accepts a root account (no parent)")
  void shouldConstructWhenParentAbsent() {
    Account a = new Account(ASSETS, "Assets", AccountType.ASSET, USD, Optional.empty(), true);
    assertEquals(ASSETS, a.code());
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }

  @Test
  @DisplayName("accepts a child account with a different-code parent")
  void shouldConstructWhenParentDiffersFromCode() {
    Account a = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.of(ASSETS), true);
    assertEquals(Optional.of(ASSETS), a.parentCode());
  }

  @Test
  @DisplayName("normalSide delegates to type")
  void shouldReturnTypeNormalSide() {
    Account a = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }
}
