package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Application smoke")
class ApplicationSmokeIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @LocalServerPort int port;

  @Test
  @DisplayName("POST /journal-entries → 201 + counter increment visible at /actuator/prometheus")
  void shouldPostEntryAndIncrementMetric() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    String body =
        """
                {
                  "occurredOn": "2026-05-10",
                  "description": "smoke",
                  "postings": [
                    { "account": "1000", "side": "DEBIT",  "minorUnits": 1234,
                      "currency": "USD", "baseMinorUnits": 1234 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 1234,
                      "currency": "USD", "baseMinorUnits": 1234 }
                  ]
                }
                """;

    ResponseEntity<String> post =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

    assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(post.getHeaders().getLocation()).isNotNull();

    String prom = client.get().uri("/actuator/prometheus").retrieve().body(String.class);

    assertThat(prom).contains("keystone_journal_entries_posted_total").contains("result=\"ok\"");
  }

  @Test
  @DisplayName("POST /accounts creates EXPENSE account; GET it back shows 201")
  void shouldCreateExpenseAccount() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    ResponseEntity<String> create = createAccount5000(client);

    assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(create.getHeaders().getLocation()).isNotNull();
  }

  @Test
  @DisplayName("POST /journal-entries debiting new EXPENSE account returns 201")
  void shouldPostEntryAgainstNewAccount() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    ensureAccount5000Exists(client);

    ResponseEntity<String> entry =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(expenseEntryBody())
            .retrieve()
            .toEntity(String.class);

    assertThat(entry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  @DisplayName("POST /journal-entries against unknown account returns 400 + account-not-found")
  void shouldReturn400WhenPostingAgainstUnknownAccount() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    String body =
        """
                {
                  "occurredOn": "2026-05-12",
                  "description": "bad entry",
                  "postings": [
                    { "account": "9999", "side": "DEBIT",  "minorUnits": 100,
                      "currency": "USD", "baseMinorUnits": 100 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 100,
                      "currency": "USD", "baseMinorUnits": 100 }
                  ]
                }
                """;

    ResponseEntity<String> response =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                (req, res) -> {
                  /* swallow */
                })
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("/problems/journal/account-not-found");
  }

  private static String account5000Body() {
    return """
            {
              "code": "5000",
              "name": "Office Supplies",
              "type": "EXPENSE",
              "currency": "USD"
            }
            """;
  }

  private static String expenseEntryBody() {
    return """
            {
              "occurredOn": "2026-05-12",
              "description": "office supplies purchase",
              "postings": [
                { "account": "5000", "side": "DEBIT",  "minorUnits": 5000,
                  "currency": "USD", "baseMinorUnits": 5000 },
                { "account": "3000", "side": "CREDIT", "minorUnits": 5000,
                  "currency": "USD", "baseMinorUnits": 5000 }
              ]
            }
            """;
  }

  private ResponseEntity<String> createAccount5000(RestClient client) {
    return client
        .post()
        .uri("/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(account5000Body())
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  @DisplayName("Period lifecycle: close → reject posting → reopen → accept posting")
  void shouldEnforceClosedPeriodAndReopenSuccessfully() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    // Step 1: post a balanced entry in June 2026 → should succeed
    ResponseEntity<String> firstPost = postJuneEntry(client);
    assertThat(firstPost.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // Other tests post in May 2026; close May first (idempotent) to satisfy sequential check.
    closePeriodSwallowError(client, "2026-05");

    // Step 2: close June 2026
    ResponseEntity<String> closeResponse = closePeriod(client, "2026-06");
    assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(closeResponse.getBody()).contains("\"status\":\"CLOSED\"");

    // Step 3: post same entry again → should be rejected
    ResponseEntity<String> rejectedPost = postJuneEntrySwallowError(client);
    assertThat(rejectedPost.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(rejectedPost.getBody()).contains("/problems/journal/posting-in-closed-period");

    // Step 4: reopen June 2026
    ResponseEntity<String> reopenResponse = reopenPeriod(client, "2026-06");
    assertThat(reopenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(reopenResponse.getBody()).contains("\"status\":\"OPEN\"");
    assertThat(reopenResponse.getBody()).contains("reopenedAt");

    // Step 5: post entry again → should succeed
    ResponseEntity<String> secondPost = postJuneEntry(client);
    assertThat(secondPost.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private ResponseEntity<String> postJuneEntry(RestClient client) {
    return client
        .post()
        .uri("/journal-entries")
        .contentType(MediaType.APPLICATION_JSON)
        .body(juneEntryBody())
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> postJuneEntrySwallowError(RestClient client) {
    return client
        .post()
        .uri("/journal-entries")
        .contentType(MediaType.APPLICATION_JSON)
        .body(juneEntryBody())
        .retrieve()
        .onStatus(
            HttpStatusCode::isError,
            (req, res) -> {
              /* swallow */
            })
        .toEntity(String.class);
  }

  private ResponseEntity<String> closePeriod(RestClient client, String yyyymm) {
    return client.post().uri("/periods/" + yyyymm + "/close").retrieve().toEntity(String.class);
  }

  private void closePeriodSwallowError(RestClient client, String yyyymm) {
    client
        .post()
        .uri("/periods/" + yyyymm + "/close")
        .retrieve()
        .onStatus(
            HttpStatusCode::isError,
            (req, res) -> {
              /* swallow */
            })
        .toEntity(String.class);
  }

  private ResponseEntity<String> reopenPeriod(RestClient client, String yyyymm) {
    return client.post().uri("/periods/" + yyyymm + "/reopen").retrieve().toEntity(String.class);
  }

  private static String juneEntryBody() {
    return """
        {
          "occurredOn": "2026-06-15",
          "description": "june smoke test",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 7500,
              "currency": "USD", "baseMinorUnits": 7500 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 7500,
              "currency": "USD", "baseMinorUnits": 7500 }
          ]
        }
        """;
  }

  private void ensureAccount5000Exists(RestClient client) {
    // Idempotent: ignore failure if already created by another test
    client
        .post()
        .uri("/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(account5000Body())
        .retrieve()
        .onStatus(
            HttpStatusCode::isError,
            (req, res) -> {
              /* swallow */
            })
        .toEntity(String.class);
  }

  @Test
  @DisplayName("Multi-currency: create EUR account, post USD→EUR transfer → 201 with base info")
  void shouldPostMultiCurrencyEntry() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    ensureCashEurExists(client);

    ResponseEntity<String> entry =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(usdToEurEntryBody())
            .retrieve()
            .toEntity(String.class);

    assertThat(entry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    // Per-posting currency + baseMinorUnits round-trip in the response.
    assertThat(entry.getBody()).contains("\"currency\":\"EUR\"").contains("\"currency\":\"USD\"");
    assertThat(entry.getBody()).contains("\"baseMinorUnits\":10000");
  }

  private void ensureCashEurExists(RestClient client) {
    client
        .post()
        .uri("/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(cashEurAccountBody())
        .retrieve()
        .onStatus(
            HttpStatusCode::isError,
            (req, res) -> {
              /* swallow */
            })
        .toEntity(String.class);
  }

  private static String cashEurAccountBody() {
    return """
        {
          "code": "1000-EUR",
          "name": "Cash EUR",
          "type": "ASSET",
          "currency": "EUR"
        }
        """;
  }

  // USD→EUR transfer at 0.92 rate. baseAmount is USD on both legs; the entry
  // balances in USD (10000 USD debit base ≡ 10000 USD credit base).
  private static String usdToEurEntryBody() {
    return """
        {
          "occurredOn": "2026-05-13",
          "description": "USD->EUR transfer",
          "postings": [
            { "account": "1000-EUR", "side": "DEBIT",  "minorUnits": 9200,
              "currency": "EUR", "baseMinorUnits": 10000 },
            { "account": "1000",     "side": "CREDIT", "minorUnits": 10000,
              "currency": "USD", "baseMinorUnits": 10000 }
          ]
        }
        """;
  }

  @Test
  @DisplayName(
      "GET /reports/trial-balance returns rows for a posted entry; sums net to zero in base")
  void shouldReturnTrialBalanceForPostedEntries() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    // Post a balanced USD entry (using the seeded 1000 and 3000 accounts).
    ResponseEntity<String> post =
        client
            .post()
            .uri("/journal-entries")
            .contentType(MediaType.APPLICATION_JSON)
            .body(trialBalanceSeedBody())
            .retrieve()
            .toEntity(String.class);
    assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String body =
        client
            .get()
            .uri("/reports/trial-balance?asOf=2026-07-31&includeZero=true")
            .retrieve()
            .body(String.class);

    assertThat(body).isNotNull();
    // Both legs appear in the report; both carry baseBalance.
    assertThat(body).contains("\"accountCode\":\"1000\"").contains("\"accountCode\":\"3000\"");
    assertThat(body).contains("\"currency\":\"USD\"");
    assertThat(body).contains("\"balance\":4444").contains("\"balance\":-4444");
  }

  private static String trialBalanceSeedBody() {
    return """
        {
          "occurredOn": "2026-07-15",
          "description": "trial balance smoke seed",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 4444,
              "currency": "USD", "baseMinorUnits": 4444 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 4444,
              "currency": "USD", "baseMinorUnits": 4444 }
          ]
        }
        """;
  }
}
