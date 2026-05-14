package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalValidationContext")
class JournalValidationContextTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  private static Account cash() {
    return new Account(
        TENANT,
        new AccountCode("1000"),
        "Cash",
        AccountType.ASSET,
        USD,
        Optional.empty(),
        AccountStatus.ACTIVE);
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
  @DisplayName("rejects null periodStatus in 4-arg constructor")
  void shouldThrowWhenPeriodStatusIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new JournalValidationContext(Map.of(), Set.of(), null, JournalValidationMode.STRICT));
  }

  @Test
  @DisplayName("rejects null mode in 5-arg constructor")
  void shouldThrowWhenModeIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JournalValidationContext(
                Map.of(), Set.of(), PeriodStatus.OPEN, USD, (JournalValidationMode) null));
  }

  @Test
  @DisplayName("two-arg constructor defaults to OPEN, STRICT mode")
  void shouldDefaultToOpenWhenTwoArgConstructorUsed() {
    JournalValidationContext ctx = new JournalValidationContext(Map.of(), Set.of());
    assertEquals(PeriodStatus.OPEN, ctx.periodStatus());
    assertEquals(JournalValidationMode.STRICT, ctx.mode());
    assertEquals(false, ctx.isPermissive());
  }

  @Test
  @DisplayName("permissive() returns an empty-accounts context in PERMISSIVE mode")
  void shouldReturnEmptyAccountsWhenPermissive() {
    JournalValidationContext ctx = JournalValidationContext.permissive();
    assertEquals(Map.of(), ctx.accounts());
    assertEquals(Set.of(), ctx.nonLeafCodes());
    assertEquals(JournalValidationMode.PERMISSIVE, ctx.mode());
    assertTrue(ctx.isPermissive());
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

  @Test
  @DisplayName("rejects null baseCurrency")
  void shouldThrowWhenBaseCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new JournalValidationContext(
                Map.of(), Set.of(), PeriodStatus.OPEN, null, JournalValidationMode.STRICT));
  }

  @Test
  @DisplayName("4-arg back-compat constructor defaults baseCurrency to USD")
  void shouldDefaultBaseCurrencyToUsdWhen4ArgConstructorUsed() {
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(), Set.of(), PeriodStatus.OPEN, JournalValidationMode.STRICT);
    assertEquals(Currency.getInstance("USD"), ctx.baseCurrency());
  }
}
