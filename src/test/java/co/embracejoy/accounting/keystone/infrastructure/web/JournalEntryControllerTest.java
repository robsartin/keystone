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
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JournalEntryController.class)
@DisplayName("JournalEntryController")
class JournalEntryControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean PostJournalEntryService service;

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
                new Posting(new AccountCode("1000"), Side.DEBIT, new Money(10000L, USD)),
                new Posting(new AccountCode("3000"), Side.CREDIT, new Money(10000L, USD))));
    JournalEntry entry = ((Result.Success<JournalEntry, JournalError>) r).value();
    return new PersistedJournalEntry(
        new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000000")), entry);
  }

  @Test
  @DisplayName("returns 201 + Location with the new id when service returns Success")
  void shouldReturn201WhenSuccess() throws Exception {
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
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.postings.length()").value(2));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(Unbalanced)")
  void shouldReturn400WhenUnbalanced() throws Exception {
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
