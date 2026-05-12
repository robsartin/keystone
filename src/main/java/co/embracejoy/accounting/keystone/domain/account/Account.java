package co.embracejoy.accounting.keystone.domain.account;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * An account in the chart of accounts.
 *
 * <p>{@link AccountCode} is the natural primary key; the user supplies it. Optional {@link
 * #parentCode()} forms a hierarchy (tree). Leaf-only posting is enforced at {@code
 * JournalEntry.of(...)} time, not here.
 */
public record Account(
    AccountCode code,
    String name,
    AccountType type,
    Currency currency,
    Optional<AccountCode> parentCode,
    boolean active) {

  public Account {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(parentCode, "parentCode");
    if (parentCode.isPresent() && parentCode.get().equals(code)) {
      throw new IllegalArgumentException("account cannot be its own parent");
    }
  }

  public NormalSide normalSide() {
    return type.normalSide();
  }
}
