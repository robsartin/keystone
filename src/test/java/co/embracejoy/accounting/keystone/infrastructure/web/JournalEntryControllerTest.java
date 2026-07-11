package co.embracejoy.accounting.keystone.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.journal.JournalEntryQueryService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.observability.MetricsConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(JournalEntryController.class)
@Import({
  MetricsConfig.class,
  JournalEntryControllerTest.BaseCurrencyTestConfig.class,
  TestSecurityConfig.class,
  SecurityConfig.class
})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("JournalEntryController")
class JournalEntryControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean PostJournalEntryService service;
  @MockitoBean JournalEntryQueryService queryService;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;
  @MockitoBean Counter journalEntriesPostedOk;
  @MockitoBean Counter journalEntriesPostedInvalid;
  @MockitoBean Timer journalEntriesPostDuration;

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  /** Provides the {@code keystoneBaseCurrency} bean (USD) for {@code @WebMvcTest}. */
  @TestConfiguration
  static class BaseCurrencyTestConfig {
    @Bean
    Currency keystoneBaseCurrency() {
      return Currency.getInstance("USD");
    }
  }

  @BeforeEach
  void setupAuth() {
    Mockito.when(tenants.findById(TENANT))
        .thenReturn(
            Optional.of(new Tenant(TENANT, "Test Tenant", Instant.now(), Optional.empty())));
    Mockito.when(platformAdmins.exists(Mockito.anyString())).thenReturn(false);
    Mockito.when(roles.findRole(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
  }

  private RequestPostProcessor withTestAuth(Role role) {
    Mockito.when(roles.findRole(TENANT, "auth0|test-user"))
        .thenReturn(
            Optional.of(
                new TenantUserRole(TENANT, "auth0|test-user", role, Instant.EPOCH, "system")));
    return req -> {
      req.addHeader("Authorization", "Bearer " + jwt.mint("auth0|test-user", TENANT));
      return req;
    };
  }

  private static String validBody() {
    return """
        {
          "occurredOn": "2026-05-10",
          "description": "opening",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 10000,
              "currency": "USD", "baseMinorUnits": 10000 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 10000,
              "currency": "USD", "baseMinorUnits": 10000 }
          ]
        }
        """;
  }

  private static PersistedJournalEntry validPersisted() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
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
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(Result.success(validPersisted()));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    "Location", endsWith("/journal-entries/01902f9f-0000-7000-8000-000000000000")))
        .andExpect(jsonPath("$.id").value("01902f9f-0000-7000-8000-000000000000"))
        .andExpect(jsonPath("$.postings.length()").value(2))
        .andExpect(jsonPath("$.postings[0].currency").value("USD"))
        .andExpect(jsonPath("$.postings[0].baseMinorUnits").value(10000));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(Unbalanced)")
  void shouldReturn400WhenUnbalanced() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(
            Result.failure(
                new JournalError.Unbalanced(new Money(10000L, USD), new Money(9000L, USD))));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
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
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(Result.failure(new JournalError.AccountNotFound(new AccountCode("9999"))));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
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
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(Result.failure(new JournalError.AccountInactive(new AccountCode("1000"))));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
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
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(Result.failure(new JournalError.AccountNotALeaf(new AccountCode("1000"))));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
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
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(
            Result.failure(
                new JournalError.AccountCurrencyMismatch(new AccountCode("4000"), USD, eur)));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/account-currency-mismatch")))
        .andExpect(jsonPath("$.detail", containsString("4000")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(BaseCurrencyMismatch)")
  void shouldReturn400WhenBaseCurrencyMismatch() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Currency eur = Currency.getInstance("EUR");
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(
            Result.failure(
                new JournalError.BaseCurrencyMismatch(new AccountCode("1000-EUR"), USD, eur)));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/base-currency-mismatch")))
        .andExpect(jsonPath("$.detail", containsString("1000-EUR")))
        .andExpect(jsonPath("$.detail", containsString("USD")))
        .andExpect(jsonPath("$.detail", containsString("EUR")));
  }

  @Test
  @DisplayName("returns 400 ProblemDetail when service returns Failure(PostingInClosedPeriod)")
  void shouldReturn400WhenPostingInClosedPeriod() throws Exception {
    Mockito.when(journalEntriesPostDuration.record(Mockito.any(Supplier.class)))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    Mockito.when(
            service.post(
                Mockito.any(TenantId.class),
                Mockito.any(LocalDate.class),
                Mockito.anyString(),
                Mockito.anyList(),
                Mockito.anyString()))
        .thenReturn(Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 5))));

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.ADMIN)))
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
          "postings": []
        }
        """;

    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.title").value(notNullValue()));
  }

  @Test
  @DisplayName("returns 401 when no Authorization header is present")
  void shouldReturn401WhenNoAuthorizationHeader() throws Exception {
    mvc.perform(
            post("/journal-entries").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("returns 403 when caller has only the READ_ONLY role")
  void shouldReturn403WhenReadOnlyTriesPost() throws Exception {
    mvc.perform(
            post("/journal-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
                .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 200 with entry")
  void shouldReturn200WhenEntryFound() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-0000000000ee"));
    PersistedJournalEntry persisted = new PersistedJournalEntry(id, validPersisted().entry());
    Mockito.when(queryService.findById(TENANT, id)).thenReturn(Optional.of(persisted));

    mvc.perform(get("/journal-entries/" + id.value()).with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.value().toString()));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 404 when entry not found")
  void shouldReturn404WhenEntryNotFound() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    Mockito.when(queryService.findById(Mockito.any(), Mockito.eq(id))).thenReturn(Optional.empty());

    mvc.perform(get("/journal-entries/" + id.value()).with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/not-found")));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 404 when path variable is not a UUID")
  void shouldReturn404WhenGetByIdWithMalformedUuid() throws Exception {
    mvc.perform(get("/journal-entries/not-a-uuid").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/journal/not-found")))
        .andExpect(jsonPath("$.detail", containsString("not-a-uuid")));
  }

  @Test
  @DisplayName("GET /journal-entries/{id} returns 401 when no auth")
  void shouldReturn401WhenNoAuth() throws Exception {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    mvc.perform(get("/journal-entries/" + id.value())).andExpect(status().isUnauthorized());
  }
}
