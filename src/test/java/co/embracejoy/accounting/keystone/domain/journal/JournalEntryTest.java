package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntry")
class JournalEntryTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final LocalDate TODAY = LocalDate.parse("2026-05-09");

  private static Posting debit(AccountCode a, long amt, Currency c) {
    Money m = new Money(amt, c);
    return new Posting(a, Side.DEBIT, m, m);
  }

  private static Posting credit(AccountCode a, long amt, Currency c) {
    Money m = new Money(amt, c);
    return new Posting(a, Side.CREDIT, m, m);
  }

  @Test
  @DisplayName("of() returns Failure(NoPostings) when postings list is empty")
  void shouldReturnNoPostingsWhenPostingsAreEmpty() {
    Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "init", List.of());
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.NoPostings.class, ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of() returns Failure(MixedCurrencies) when postings span currencies")
  void shouldReturnMixedCurrenciesWhenCurrenciesDiffer() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 100L, USD), credit(EQUITY, 100L, EUR)));
    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.MixedCurrencies.class,
        ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of() returns Failure(Unbalanced) when debits != credits")
  void shouldReturnUnbalancedWhenDebitsAndCreditsDiffer() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 100L, USD), credit(EQUITY, 90L, USD)));
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
            TODAY, "opening", List.of(debit(CASH, 10000L, USD), credit(EQUITY, 10000L, USD)));
    assertInstanceOf(Result.Success.class, r);
    JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
    assertEquals(TODAY, je.occurredOn());
    assertEquals("opening", je.description());
    assertEquals(2, je.postings().size());
    assertEquals(USD, je.currency());
  }

  @Test
  @DisplayName("of() returns Success when balanced across multiple postings")
  void shouldReturnSuccessWhenBalancedAcrossManyPostings() {
    AccountCode receivable = new AccountCode("1100");
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
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
        () -> JournalEntry.of(null, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
  }

  @Test
  @DisplayName("of() rejects null description")
  void shouldThrowWhenDescriptionIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> JournalEntry.of(TODAY, null, List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
  }

  @Test
  @DisplayName("of() rejects null postings list")
  void shouldThrowWhenPostingsListIsNull() {
    assertThrows(NullPointerException.class, () -> JournalEntry.of(TODAY, "x", null));
  }

  @Test
  @DisplayName("postings list is defensively copied and unmodifiable")
  void shouldExposeUnmodifiablePostings() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)));
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
        JournalEntry.of(TODAY, "overflow", List.of(bigDebit1, bigDebit2, smallCredit));

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
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountNotFound e =
        (JournalError.AccountNotFound) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountInactive) when account is deactivated")
  void shouldReturnAccountInactiveWhenAccountInactive() {
    Account cashInactive =
        new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), false);
    Account equity = new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cashInactive, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    assertInstanceOf(
        JournalError.AccountInactive.class,
        ((Result.Failure<JournalEntry, JournalError>) r).error());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountNotALeaf) when account has children")
  void shouldReturnAccountNotALeafWhenAccountHasChildren() {
    Account cash = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity = new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of(CASH));
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountNotALeaf e =
        (JournalError.AccountNotALeaf) ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(CASH, e.code());
  }

  @Test
  @DisplayName("of(ctx) returns Failure(AccountCurrencyMismatch) when currency differs")
  void shouldReturnAccountCurrencyMismatchWhenCurrencyDiffers() {
    Currency eur = Currency.getInstance("EUR");
    Account cashEur = new Account(CASH, "Cash EUR", AccountType.ASSET, eur, Optional.empty(), true);
    Account equityEur =
        new Account(EQUITY, "Equity EUR", AccountType.EQUITY, eur, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cashEur, EQUITY, equityEur), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.AccountCurrencyMismatch e =
        (JournalError.AccountCurrencyMismatch)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(eur, e.expectedByAccount());
    assertEquals(USD, e.actualOnPosting());
  }

  @Test
  @DisplayName("of(ctx) returns Success when all account checks pass")
  void shouldReturnSuccessWhenAllAccountChecksPass() {
    Account cash = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity = new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(Map.of(CASH, cash, EQUITY, equity), Set.of());
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TODAY, "opening", List.of(debit(CASH, 100L, USD), credit(EQUITY, 100L, USD)), ctx);
    assertInstanceOf(Result.Success.class, r);
  }

  @Test
  @DisplayName("of(ctx) returns Failure(PostingInClosedPeriod) when periodStatus is CLOSED")
  void shouldReturnPostingInClosedPeriodWhenPeriodClosed() {
    Account cash = new Account(CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), true);
    Account equity = new Account(EQUITY, "Equity", AccountType.EQUITY, USD, Optional.empty(), true);
    JournalValidationContext ctx =
        new JournalValidationContext(
            Map.of(CASH, cash, EQUITY, equity), Set.of(), PeriodStatus.CLOSED, false);
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(TODAY, "x", List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)), ctx);
    JournalError.PostingInClosedPeriod e =
        (JournalError.PostingInClosedPeriod)
            ((Result.Failure<JournalEntry, JournalError>) r).error();
    assertEquals(YearMonth.from(TODAY), e.period());
  }
}
