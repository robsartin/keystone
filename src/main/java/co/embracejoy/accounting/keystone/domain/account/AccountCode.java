package co.embracejoy.accounting.keystone.domain.account;

import java.util.Objects;

/**
 * A string-typed identifier for a ledger account in the keystone phase.
 *
 * <p>The full {@code Account} aggregate (type, normal side, hierarchy) is deferred to a later
 * slice; for now we carry just the code.
 */
public record AccountCode(String value) {

  public AccountCode {
    Objects.requireNonNull(value, "value");
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("AccountCode value must not be blank");
    }
    value = trimmed;
  }
}
