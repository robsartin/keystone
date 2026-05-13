package co.embracejoy.accounting.keystone.infrastructure.web.account.dto;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;

public record AccountResponse(
    String code, String name, String type, String currency, String parentCode, boolean active) {

  public static AccountResponse of(Account a) {
    return new AccountResponse(
        a.code().value(),
        a.name(),
        a.type().name(),
        a.currency().getCurrencyCode(),
        a.parentCode().map(AccountCode::value).orElse(null),
        a.isActive());
  }
}
