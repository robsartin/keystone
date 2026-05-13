package co.embracejoy.accounting.keystone.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeystoneProperties")
class KeystonePropertiesTest {

  @Test
  @DisplayName("baseCurrency defaults to USD when null supplied")
  void shouldDefaultBaseCurrencyToUsdWhenNull() {
    KeystoneProperties p = new KeystoneProperties(null);
    assertEquals(Currency.getInstance("USD"), p.baseCurrency());
  }

  @Test
  @DisplayName("baseCurrency is whatever is supplied when non-null")
  void shouldUseSuppliedBaseCurrencyWhenNonNull() {
    KeystoneProperties p = new KeystoneProperties(Currency.getInstance("EUR"));
    assertEquals(Currency.getInstance("EUR"), p.baseCurrency());
  }
}
