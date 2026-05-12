package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import java.util.Currency;
import java.util.Optional;

final class AccountEntityMapper {

  private AccountEntityMapper() {
    // static utility class; no instances
  }

  static AccountEntity toEntity(Account a) {
    return new AccountEntity(
        a.code().value(),
        a.name(),
        a.type().name(),
        a.currency().getCurrencyCode(),
        a.parentCode().map(AccountCode::value).orElse(null),
        a.active());
  }

  static Account toDomain(AccountEntity e) {
    return new Account(
        new AccountCode(e.getCode()),
        e.getName(),
        AccountType.valueOf(e.getType()),
        Currency.getInstance(e.getCurrency()),
        Optional.ofNullable(e.getParentCode()).map(AccountCode::new),
        e.isActive());
  }
}
