package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
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
}
