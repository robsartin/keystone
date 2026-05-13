package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.observability.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JournalEntryController.class)
@Import(MetricsConfig.class)
@DisplayName("JournalEntryController")
class JournalEntryControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean PostJournalEntryService service;
  @MockitoBean Counter journalEntriesPostedOk;
  @MockitoBean Counter journalEntriesPostedInvalid;
  @MockitoBean Timer journalEntriesPostDuration;

  private static final Currency USD = Currency.getInstance("USD");

  private static String validBody() {
    return """
        {
          "occurredOn": "2026-05-10",
          "description": "opening",
          "currency": "USD",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
          ]
        }
        """;
  }

  private static PersistedJournalEntry validPersisted() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            LocalDate.parse("2026-05-10"),
            "opening",
            List.of(
                new Posting(
                    new AccountCode("1000"),
                    Side.DEBIT,
                    new Money(10000L, USD),
                    new Money(10000L, USD)),
                new Posting(
                    new AccountCode("3000"),
                    Side.CREDIT,
                    new Money(10000L, USD),
                    new Money(10000L, USD))));
    JournalEntry entry = ((Result.Success<JournalEntry, JournalError>) r).value();
    return new PersistedJournalEntry(
        new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000000")), entry);
  }

  @Test
  @DisplayName("returns 201 + Location with the new id when service returns Success")
  void shouldReturn201WhenSuccess() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(Result.success(validPersisted()));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    "Location", endsWith("/journal-entries/01902f9f-0000-7000-8000-000000000000")))
        .andExpect(jsonPath("$.id").value("01902f9f-0000-7000-8000-000000000000"))
        .andExpect(jsonPath("$.postings.length()").value(2));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(Unbalanced)")
  void shouldReturn400WhenUnbalanced() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(
            Result.failure(
                new JournalError.Unbalanced(new Money(10000L, USD), new Money(9000L, USD))));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/unbalanced")))
        .andExpect(jsonPath("$.title").value("Journal entry is not balanced"))
        .andExpect(jsonPath("$.detail", containsString("10000")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(AccountNotFound)")
  void shouldReturn400WhenAccountNotFound() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(Result.failure(new JournalError.AccountNotFound(new AccountCode("9999"))));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/account-not-found")))
        .andExpect(jsonPath("$.detail", containsString("9999")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(AccountInactive)")
  void shouldReturn400WhenAccountInactive() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(Result.failure(new JournalError.AccountInactive(new AccountCode("1000"))));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/account-inactive")))
        .andExpect(jsonPath("$.detail", containsString("1000")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(AccountNotALeaf)")
  void shouldReturn400WhenAccountNotALeaf() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(Result.failure(new JournalError.AccountNotALeaf(new AccountCode("1000"))));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/account-not-a-leaf")))
        .andExpect(jsonPath("$.detail", containsString("1000")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(AccountCurrencyMismatch)")
  void shouldReturn400WhenAccountCurrencyMismatch() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Currency eur = Currency.getInstance("EUR");
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(
            Result.failure(
                new JournalError.AccountCurrencyMismatch(new AccountCode("4000"), USD, eur)));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/account-currency-mismatch")))
        .andExpect(jsonPath("$.detail", containsString("4000")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(PostingInClosedPeriod)")
  void shouldReturn400WhenPostingInClosedPeriod() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(service.post(Mockito.any(LocalDate.class), Mockito.anyString(), Mockito.anyList()))
        .thenReturn(Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 5))));

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/posting-in-closed-period")))
        .andExpect(jsonPath("$.detail", containsString("2026-05")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when Bean Validation rejects the request")
  void shouldReturn400WhenValidationFails() throws Exception {
    String invalidBody =
        """
        {
          "occurredOn": "2026-05-10",
          "description": "",
          "currency": "usd",
          "postings": []
        }
        """;

    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(invalidBody))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(notNullValue()));
  }
}
