package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Account")
class AccountTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode ASSETS = new AccountCode("1");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  @Test
  @DisplayName("rejects null tenantId")
  void shouldThrowWhenTenantIdIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Account(
                null,
                CASH,
                "Cash",
                AccountType.ASSET,
                USD,
                Optional.empty(),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null code")
  void shouldThrowWhenCodeIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Account(
                TENANT,
                null,
                "Cash",
                AccountType.ASSET,
                USD,
                Optional.empty(),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null name")
  void shouldThrowWhenNameIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Account(
                TENANT,
                CASH,
                null,
                AccountType.ASSET,
                USD,
                Optional.empty(),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects blank name")
  void shouldThrowWhenNameIsBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Account(
                TENANT,
                CASH,
                "  ",
                AccountType.ASSET,
                USD,
                Optional.empty(),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null type")
  void shouldThrowWhenTypeIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(TENANT, CASH, "Cash", null, USD, Optional.empty(), AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null currency")
  void shouldThrowWhenCurrencyIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Account(
                TENANT,
                CASH,
                "Cash",
                AccountType.ASSET,
                null,
                Optional.empty(),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null parentCode Optional")
  void shouldThrowWhenParentOptionalIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Account(TENANT, CASH, "Cash", AccountType.ASSET, USD, null, AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("rejects null status")
  void shouldThrowWhenStatusIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new Account(TENANT, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), null));
  }

  @Test
  @DisplayName("rejects self-parent")
  void shouldThrowWhenAccountIsItsOwnParent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Account(
                TENANT,
                CASH,
                "Cash",
                AccountType.ASSET,
                USD,
                Optional.of(CASH),
                AccountStatus.ACTIVE));
  }

  @Test
  @DisplayName("accepts a root account (no parent)")
  void shouldConstructWhenParentAbsent() {
    Account a =
        new Account(
            TENANT,
            ASSETS,
            "Assets",
            AccountType.ASSET,
            USD,
            Optional.empty(),
            AccountStatus.ACTIVE);
    assertEquals(ASSETS, a.code());
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }

  @Test
  @DisplayName("accepts a child account with a different-code parent")
  void shouldConstructWhenParentDiffersFromCode() {
    Account a =
        new Account(
            TENANT,
            CASH,
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.of(ASSETS),
            AccountStatus.ACTIVE);
    assertEquals(Optional.of(ASSETS), a.parentCode());
  }

  @Test
  @DisplayName("normalSide delegates to type")
  void shouldReturnTypeNormalSide() {
    Account a =
        new Account(
            TENANT, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.ACTIVE);
    assertEquals(NormalSide.DEBIT, a.normalSide());
  }

  @Test
  @DisplayName("isActive() is true iff status is ACTIVE")
  void shouldReportActiveStatus() {
    Account active =
        new Account(
            TENANT, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.ACTIVE);
    Account inactive =
        new Account(
            TENANT, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.INACTIVE);
    assertTrue(active.isActive());
    assertFalse(inactive.isActive());
  }
}
