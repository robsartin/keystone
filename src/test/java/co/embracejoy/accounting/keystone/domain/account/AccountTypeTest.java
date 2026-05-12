package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountType")
class AccountTypeTest {

  @Test
  @DisplayName("ASSET has normal side DEBIT")
  void shouldReturnDebitWhenTypeIsAsset() {
    assertEquals(NormalSide.DEBIT, AccountType.ASSET.normalSide());
  }

  @Test
  @DisplayName("EXPENSE has normal side DEBIT")
  void shouldReturnDebitWhenTypeIsExpense() {
    assertEquals(NormalSide.DEBIT, AccountType.EXPENSE.normalSide());
  }

  @Test
  @DisplayName("LIABILITY has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsLiability() {
    assertEquals(NormalSide.CREDIT, AccountType.LIABILITY.normalSide());
  }

  @Test
  @DisplayName("EQUITY has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsEquity() {
    assertEquals(NormalSide.CREDIT, AccountType.EQUITY.normalSide());
  }

  @Test
  @DisplayName("REVENUE has normal side CREDIT")
  void shouldReturnCreditWhenTypeIsRevenue() {
    assertEquals(NormalSide.CREDIT, AccountType.REVENUE.normalSide());
  }
}
