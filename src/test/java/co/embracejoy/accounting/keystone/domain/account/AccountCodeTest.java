package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountCode")
class AccountCodeTest {

  @Test
  @DisplayName("rejects null value")
  void shouldThrowWhenValueIsNull() {
    assertThrows(NullPointerException.class, () -> new AccountCode(null));
  }

  @Test
  @DisplayName("rejects blank value")
  void shouldThrowWhenValueIsBlank() {
    assertThrows(IllegalArgumentException.class, () -> new AccountCode("   "));
  }

  @Test
  @DisplayName("rejects empty value")
  void shouldThrowWhenValueIsEmpty() {
    assertThrows(IllegalArgumentException.class, () -> new AccountCode(""));
  }

  @Test
  @DisplayName("trims surrounding whitespace")
  void shouldTrimSurroundingWhitespaceWhenConstructed() {
    assertEquals(new AccountCode("1000"), new AccountCode("  1000  "));
  }

  @Test
  @DisplayName("equality is case-sensitive: same case after trim is equal")
  void shouldBeEqualWhenSameCaseAfterTrim() {
    assertEquals(new AccountCode("AR-CASH"), new AccountCode("AR-CASH"));
  }

  @Test
  @DisplayName("equality is case-sensitive: different case is not equal")
  void shouldDifferByCaseWhenComparedAfterTrim() {
    org.junit.jupiter.api.Assertions.assertNotEquals(
        new AccountCode("AR-CASH"), new AccountCode("ar-cash"));
  }
}
