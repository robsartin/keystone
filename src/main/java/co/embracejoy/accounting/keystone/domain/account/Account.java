package co.embracejoy.accounting.keystone.domain.account;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
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
    TenantId tenantId,
    AccountCode code,
    String name,
    AccountType type,
    Currency currency,
    Optional<AccountCode> parentCode,
    AccountStatus status) {

  public Account {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(parentCode, "parentCode");
    Objects.requireNonNull(status, "status");
    if (parentCode.isPresent() && parentCode.get().equals(code)) {
      throw new IllegalArgumentException("account cannot be its own parent");
    }
  }

  public NormalSide normalSide() {
    return type.normalSide();
  }

  /** Convenience read accessor: {@code true} iff {@link #status()} is {@code ACTIVE}. */
  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }
}
