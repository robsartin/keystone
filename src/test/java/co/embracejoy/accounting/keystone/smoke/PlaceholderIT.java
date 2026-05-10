package co.embracejoy.accounting.keystone.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Failsafe wiring smoke")
class PlaceholderIT {

  @Test
  @DisplayName("placeholder IT runs via Failsafe")
  void shouldRunViaFailsafe() {
    assertTrue(true);
  }
}
