package co.embracejoy.accounting.keystone.infrastructure.config;

import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration root for keystone-wide application settings.
 *
 * <p>Bound to the {@code keystone} prefix in {@code application.yaml}:
 *
 * <pre>
 * keystone:
 *   base-currency: USD
 * </pre>
 *
 * <p>Defaults to USD when {@code base-currency} is absent or null.
 */
@ConfigurationProperties("keystone")
public record KeystoneProperties(Currency baseCurrency) {

  public KeystoneProperties {
    if (baseCurrency == null) {
      baseCurrency = Currency.getInstance("USD");
    }
  }
}
