package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntry")
class JournalEntryTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final LocalDate TODAY = LocalDate.parse("2026-05-09");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  private static Posting debit(AccountCode a, long amt, Currency c) {
    Money m = new Money(amt, c);
    return new Posting(a, Side.DEBIT, m, m);
  }

  private static Posting credit(AccountCode a, long amt, Currency c) {
    Money m = new Money(amt, c);
    return new Posting(a, Side.CREDIT, m, m);
  }

  private static Account account(AccountCode code, String name, AccountType type, Currency ccy) {
    return new Account(TENANT, code, name, type, ccy, Optional.empty(), AccountStatus.ACTIVE);
  }

  private static Account account(AccountCode code, String name, AccountType type) {
    return account(code, name, type, USD);
  }

  @Test
  @DisplayName("of() rejects null tenantId")
  void shouldThrowWhenTenantIdIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            JournalEntry.of(
                null, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
  }

  @Test
  @DisplayName("of() returns Failure(NoPostings) when postings list is empty")
  void shouldReturnNoPostingsWhenPostingsAreEmpty() {
    Result<JournalEntry, JournalError> r = JournalEntry.of(TENANT, TODAY, "init", List.of());
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.NoPostings.class, ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of() returns Failure(Unbalanced) when debits != credits")
  void shouldReturnUnbalancedWhenDebitsAndCreditsDiffer() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 100L, USD), credit(EQUITY, 90L, USD)));
    assertInstanceOf(Result.Failure.class, r);
    JournalError.Unbalanced u =
        (JournalError.Unbalanced) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(new Money(100L, USD), u.debits());
    assertEquals(new Money(90L, USD), u.credits());
  }

  @Test
  @DisplayName("of() returns Success when balanced")
  void shouldReturnSuccessWhenBalanced() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            TODAY,
            "opening",
            List.of(debit(CASH, 10000L, USD), credit(EQUITY, 10000L, USD)));
    assertInstanceOf(Result.Success.class, r);
    JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
    assertEquals(TENANT, je.tenantId());
    assertEquals(TODAY, je.occurredOn());
    assertEquals("opening", je.description());
    assertEquals(2, je.postings().size());
  }

  @Test
  @DisplayName("of() returns Success when balanced across multiple postings")
  void shouldReturnSuccessWhenBalancedAcrossManyPostings() {
    AccountCode receivable = new AccountCode("1100");
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            TODAY,
            "split",
            List.of(
                debit(CASH, 600L, USD), debit(receivable, 400L, USD), credit(EQUITY, 1000L, USD)));
    assertInstanceOf(Result.Success.class, r);
  }

  @Test
  @DisplayName("of() rejects null occurredOn")
  void shouldThrowWhenOccurredOnIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            JournalEntry.of(
                TENANT, null, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
  }

  @Test
  @DisplayName("of() rejects null description")
  void shouldThrowWhenDescriptionIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            JournalEntry.of(
                TENANT, TODAY, null, List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
  }

  @Test
  @DisplayName("of() rejects null postings list")
  void shouldThrowWhenPostingsListIsNull() {
    assertThrows(NullPointerException.class, () -> JournalEntry.of(TENANT, TODAY, "x", null));
  }

  @Test
  @DisplayName("postings list is defensively copied and unmodifiable")
  void shouldExposeUnmodifiablePostings() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)));
    JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
    assertThrows(
        UnsupportedOperationException.class, () -> je.postings().add(debit(CASH, 5L, USD)));
  }

  @Test
  @DisplayName("of() returns Failure(Overflow) when same-side postings sum past Long.MAX_VALUE")
  void shouldReturnOverflowWhenSameSidePostingsExceedLongRange() {
    long half = Long.MAX_VALUE / 2 + 1;
    Posting bigDebit1 = new Posting(CASH, Side.DEBIT, new Money(half, USD), new Money(half, USD));
    AccountCode receivable = new AccountCode("1100");
    Posting bigDebit2 =
        new Posting(receivable, Side.DEBIT, new Money(half, USD), new Money(half, USD));
    Posting smallCredit = new Posting(EQUITY, Side.CREDIT, new Money(1L, USD), new Money(1L, USD));

    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TENANT, TODAY, "overflow", List.of(bigDebit1, bigDebit2, smallCredit));

    assertInstanceOf(Result.Failure.class, r);
    JournalError e = ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertInstanceOf(JournalError.Overflow.class, e);
    assertEquals(Side.DEBIT, ((JournalError.Overflow) e).side());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountNotFound) when a posting account is missing")
  void shouldReturnAccountNotFoundWhenAccountAbsent() {
    JournalValidationContext ctx = new JournalValidationContext(Map.of(), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountNotFound e =
        (JournalError.AccountNotFound) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountInactive) when account is deactivated")
  void shouldReturnAccountInactiveWhenAccountInactive() {
    Account cashInactive =
        new Account(
            TENANT, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.INACTIVE);
    Account equity = account(EQUITY, "Equity", AccountType.EQUITY);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cashInactive, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    assertInstanceOf(
        JournalError.AccountInactive.class,
        ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountNotALeaf) when account has children")
  void shouldReturnAccountNotALeafWhenAccountHasChildren() {
    Account cash = account(CASH, "Cash", AccountType.ASSET);
    Account equity = account(EQUITY, "Equity", AccountType.EQUITY);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of(CASH));
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountNotALeaf e =
        (JournalError.AccountNotALeaf) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountCurrencyMismatch) when currency differs")
  void shouldReturnAccountCurrencyMismatchWhenCurrencyDiffers() {
    Currency eur = Currency.getInstance("EUR");
    Account cashEur = account(CASH, "Cash EUR", AccountType.ASSET, eur);
    Account equityEur = account(EQUITY, "Equity EUR", AccountType.EQUITY, eur);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cashEur, EQUITY, equityEur), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountCurrencyMismatch e =
        (JournalError.AccountCurrencyMismatch)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(eur, e.expectedByAccount());
    assertEquals(USD, e.actualOnPosting());
  }

  @Test
  @DisplayName("of(ctx) returns Success when all account checks pass")
  void shouldReturnSuccessWhenAllAccountChecksPass() {
    Account cash = account(CASH, "Cash", AccountType.ASSET);
    Account equity = account(EQUITY, "Equity", AccountType.EQUITY);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            TODAY,
            "opening",
            List.of(debit(CASH, 100L, USD), credit(EQUITY, 100L, USD)),
            ctx);
    assertInstanceOf(Result.Success.class, r);
  }

  @Test
  @DisplayName("of(ctx) returns Failure(PostingInClosedPeriod) when periodStatus is CLOSED")
  void shouldReturnPostingInClosedPeriodWhenPeriodClosed() {
    Account cash = account(CASH, "Cash", AccountType.ASSET);
    Account equity = account(EQUITY, "Equity", AccountType.EQUITY);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cash, EQUITY, equity),
            Set.of(),
            PeriodStatus.CLOSED,
            JournalValidationMode.STRICT);
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT, TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.PostingInClosedPeriod e =
        (JournalError.PostingInClosedPeriod)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(YearMonth.from(TODAY), e.period());
  }

  @Test
  @DisplayName(
      "of(ctx) returns Failure(BaseCurrencyMismatch) when posting baseAmount currency differs")
  void shouldReturnBaseCurrencyMismatchWhenBaseCurrencyDiffers() {
    Currency eur = Currency.getInstance("EUR");
    Account cash = account(CASH, "Cash", AccountType.ASSET);
    Account equity = account(EQUITY, "Equity", AccountType.EQUITY);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cash, EQUITY, equity),
            Set.of(),
            PeriodStatus.OPEN,
            Currency.getInstance("USD"),
            JournalValidationMode.STRICT);
    // Posting carries baseAmount in EUR but the configured base is USD.
    Posting bad = new Posting(CASH, Side.DEBIT, new Money(100L, USD), new Money(92L, eur));
    Posting good = new Posting(EQUITY, Side.CREDIT, new Money(100L, USD), new Money(100L, USD));

    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TENANT, TODAY, "x", List.of(bad, good), ctx);

    JournalError.BaseCurrencyMismatch e =
        (JournalError.BaseCurrencyMismatch)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
    assertEquals(Currency.getInstance("USD"), e.expectedByConfig());
    assertEquals(eur, e.actualOnPosting());
  }

  @Test
  @DisplayName("of(ctx) returns Success for a multi-currency entry that balances in base")
  void shouldReturnSuccessWhenMultiCurrencyBalancesInBase() {
    Currency eur = Currency.getInstance("EUR");
    AccountCode cashEurCode = new AccountCode("1000-EUR");
    Account cashUsd = account(CASH, "Cash USD", AccountType.ASSET);
    Account cashEur = account(cashEurCode, "Cash EUR", AccountType.ASSET, eur);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cashUsd, cashEurCode, cashEur),
            Set.of(),
            PeriodStatus.OPEN,
            USD,
            JournalValidationMode.STRICT);
    // USD → EUR transfer at 0.92 rate: 100 USD = 92 EUR.
    // baseAmount on both is the USD-equivalent.
    Posting debitEur =
        new Posting(cashEurCode, Side.DEBIT, new Money(9200L, eur), new Money(10000L, USD));
    Posting creditUsd =
        new Posting(CASH, Side.CREDIT, new Money(10000L, USD), new Money(10000L, USD));

    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TENANT, TODAY, "USD→EUR transfer", List.of(debitEur, creditUsd), ctx);

    assertInstanceOf(Result.Success.class, r);
  }
}
