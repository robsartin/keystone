package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Side")
class SideTest {

  @Test
  @DisplayName("opposite() returns CREDIT when DEBIT")
  void shouldReturnCreditWhenOppositeOfDebit() {
    assertThat(Side.DEBIT.opposite()).isEqualTo(Side.CREDIT);
  }

  @Test
  @DisplayName("opposite() returns DEBIT when CREDIT")
  void shouldReturnDebitWhenOppositeOfCredit() {
    assertThat(Side.CREDIT.opposite()).isEqualTo(Side.DEBIT);
  }
}
