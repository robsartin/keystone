package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.period.PeriodError;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName("ResultMapper")
class ResultMapperTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  @DisplayName("NoPostings maps to 400 with type URI .../journal/no-postings")
  void shouldMapNoPostingsToProblemDetail() {
    ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.NoPostings());

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getTitle()).isEqualTo("Journal entry has no postings");
    assertThat(pd.getType().toString()).endsWith("/journal/no-postings");
  }

  @Test
  @DisplayName("MixedCurrencies maps to 400 with currencies in detail")
  void shouldMapMixedCurrenciesToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new JournalError.MixedCurrencies(Set.of(USD, Currency.getInstance("EUR"))));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/mixed-currencies");
    assertThat(pd.getDetail()).contains("USD").contains("EUR");
  }

  @Test
  @DisplayName("Unbalanced maps to 400 with debit/credit sums in detail")
  void shouldMapUnbalancedToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new JournalError.Unbalanced(new Money(10000L, USD), new Money(9000L, USD)));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/unbalanced");
    assertThat(pd.getDetail()).contains("10000").contains("9000").contains("USD");
  }

  @Test
  @DisplayName("Overflow maps to 400 with offending side in detail")
  void shouldMapOverflowToProblemDetail() {
    ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.Overflow(Side.DEBIT));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/overflow");
    assertThat(pd.getDetail()).contains("DEBIT");
  }

  // --- AccountError variants ---

  @Test
  @DisplayName("CodeAlreadyExists maps to 400 with code in detail")
  void shouldMapCodeAlreadyExistsToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new AccountError.CodeAlreadyExists(new AccountCode("1000")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/account/code-already-exists");
    assertThat(pd.getTitle()).isEqualTo("Account code already exists");
    assertThat(pd.getDetail()).contains("1000");
  }

  @Test
  @DisplayName("NotFound maps to 404 with code in detail")
  void shouldMapAccountNotFoundToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new AccountError.NotFound(new AccountCode("9999")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(pd.getType().toString()).endsWith("/account/not-found");
    assertThat(pd.getTitle()).isEqualTo("Account not found");
    assertThat(pd.getDetail()).contains("9999");
  }

  @Test
  @DisplayName("ParentNotFound maps to 400 with parent code in detail")
  void shouldMapParentNotFoundToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new AccountError.ParentNotFound(new AccountCode("8000")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/account/parent-not-found");
    assertThat(pd.getDetail()).contains("8000");
  }

  @Test
  @DisplayName("CycleWouldBeCreated maps to 400 with child and proposed parent in detail")
  void shouldMapCycleWouldBeCreatedToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new AccountError.CycleWouldBeCreated(new AccountCode("1000"), new AccountCode("1001")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/account/cycle-would-be-created");
    assertThat(pd.getDetail()).contains("1000").contains("1001");
  }

  @Test
  @DisplayName("CodeInUseByPosting maps to 400 with code in detail")
  void shouldMapCodeInUseByPostingToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new AccountError.CodeInUseByPosting(new AccountCode("1000")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/account/code-in-use-by-posting");
    assertThat(pd.getDetail()).contains("1000");
  }

  // --- New JournalError variants ---

  @Test
  @DisplayName("JournalError.AccountNotFound maps to 400 with code in detail")
  void shouldMapJournalAccountNotFoundToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new JournalError.AccountNotFound(new AccountCode("9999")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/account-not-found");
    assertThat(pd.getDetail()).contains("9999");
  }

  @Test
  @DisplayName("JournalError.AccountInactive maps to 400 with code in detail")
  void shouldMapJournalAccountInactiveToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new JournalError.AccountInactive(new AccountCode("1000")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/account-inactive");
    assertThat(pd.getDetail()).contains("1000");
  }

  @Test
  @DisplayName("JournalError.AccountNotALeaf maps to 400 with code in detail")
  void shouldMapJournalAccountNotALeafToProblemDetail() {
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new JournalError.AccountNotALeaf(new AccountCode("1000")));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/account-not-a-leaf");
    assertThat(pd.getDetail()).contains("1000");
  }

  @Test
  @DisplayName("JournalError.AccountCurrencyMismatch maps to 400 with codes in detail")
  void shouldMapJournalAccountCurrencyMismatchToProblemDetail() {
    Currency eur = Currency.getInstance("EUR");
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new JournalError.AccountCurrencyMismatch(new AccountCode("4000"), USD, eur));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/account-currency-mismatch");
    assertThat(pd.getDetail()).contains("4000").contains("USD").contains("EUR");
  }

  // --- PeriodError variants ---

  @Test
  @DisplayName("PeriodError.NotSequentiallyClosable maps to 400 with months in detail")
  void shouldMapNotSequentiallyClosableToProblemDetail() {
    YearMonth attempted = YearMonth.of(2026, 6);
    YearMonth earliest = YearMonth.of(2026, 5);
    ProblemDetail pd =
        ResultMapper.toProblemDetail(new PeriodError.NotSequentiallyClosable(attempted, earliest));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/period/not-sequentially-closable");
    assertThat(pd.getTitle()).isEqualTo("Period close is out of order");
    assertThat(pd.getDetail()).contains("2026-06").contains("2026-05");
  }

  @Test
  @DisplayName("PeriodError.NotMostRecentlyClosed maps to 400 with periods in detail")
  void shouldMapNotMostRecentlyClosedToProblemDetail() {
    YearMonth attempted = YearMonth.of(2026, 5);
    YearMonth latest = YearMonth.of(2026, 6);
    ProblemDetail pd =
        ResultMapper.toProblemDetail(
            new PeriodError.NotMostRecentlyClosed(attempted, Optional.of(latest)));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/period/not-most-recently-closed");
    assertThat(pd.getDetail()).contains("2026-05").contains("2026-06");
  }

  @Test
  @DisplayName("PeriodError.NotFound maps to 404 with yearMonth in detail")
  void shouldMapPeriodNotFoundToProblemDetail() {
    YearMonth ym = YearMonth.of(2026, 5);
    ProblemDetail pd = ResultMapper.toProblemDetail(new PeriodError.NotFound(ym));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(pd.getType().toString()).endsWith("/period/not-found");
    assertThat(pd.getTitle()).isEqualTo("Period not found");
    assertThat(pd.getDetail()).contains("2026-05");
  }

  @Test
  @DisplayName("JournalError.PostingInClosedPeriod maps to 400 with period in detail")
  void shouldMapPostingInClosedPeriodToProblemDetail() {
    YearMonth period = YearMonth.of(2026, 5);
    ProblemDetail pd = ResultMapper.toProblemDetail(new JournalError.PostingInClosedPeriod(period));

    assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(pd.getType().toString()).endsWith("/journal/posting-in-closed-period");
    assertThat(pd.getTitle()).isEqualTo("Posting falls in a closed period");
    assertThat(pd.getDetail()).contains("2026-05");
  }
}
