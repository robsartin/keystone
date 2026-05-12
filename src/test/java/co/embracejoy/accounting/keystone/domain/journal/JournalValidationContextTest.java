package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalValidationContext")
class JournalValidationContextTest {

  private static final Currency USD = Currency.getInstance("USD");

  private static Account cash() {
    return new Account(
        new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty(), true);
  }

  @Test
  @DisplayName("rejects null accounts map")
  void shouldThrowWhenAccountsNull() {
    assertThrows(NullPointerException.class, () -> new JournalValidationContext(null, Set.of()));
  }

  @Test
  @DisplayName("rejects null nonLeafCodes set")
  void shouldThrowWhenNonLeafCodesNull() {
    assertThrows(NullPointerException.class, () -> new JournalValidationContext(Map.of(), null));
  }

  @Test
  @DisplayName("permissive() returns an empty-accounts context")
  void shouldReturnEmptyAccountsWhenPermissive() {
    JournalValidationContext ctx = JournalValidationContext.permissive();
    assertEquals(Map.of(), ctx.accounts());
    assertEquals(Set.of(), ctx.nonLeafCodes());
  }

  @Test
  @DisplayName("accounts map is defensively copied")
  void shouldDefensivelyCopyAccounts() {
    Map<AccountCode, Account> mutable = new HashMap<>();
    Account a = cash();
    mutable.put(a.code(), a);
    JournalValidationContext ctx = new JournalValidationContext(mutable, Set.of());
    mutable.clear();
    assertEquals(1, ctx.accounts().size());
    assertSame(a, ctx.accounts().get(a.code()));
  }

  @Test
  @DisplayName("accounts map is unmodifiable")
  void shouldRejectModificationOfAccounts() {
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(cash().code(), cash()), Set.of());
    assertThrows(UnsupportedOperationException.class, () -> ctx.accounts().clear());
  }

  @Test
  @DisplayName("nonLeafCodes set is unmodifiable")
  void shouldRejectModificationOfNonLeafCodes() {
    AccountCode code = new AccountCode("1");
    JournalValidationContext ctx = new JournalValidationContext(Map.of(), Set.of(code));
    assertThrows(UnsupportedOperationException.class, () -> ctx.nonLeafCodes().clear());
  }
}
