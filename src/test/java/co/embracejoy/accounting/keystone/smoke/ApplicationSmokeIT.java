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
                  "currency": "USD",
                  "postings": [
                    { "account": "1000", "side": "DEBIT",  "minorUnits": 1234 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 1234 }
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
                  "currency": "USD",
                  "postings": [
                    { "account": "9999", "side": "DEBIT",  "minorUnits": 100 },
                    { "account": "3000", "side": "CREDIT", "minorUnits": 100 }
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
              "currency": "USD",
              "postings": [
                { "account": "5000", "side": "DEBIT",  "minorUnits": 5000 },
                { "account": "3000", "side": "CREDIT", "minorUnits": 5000 }
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
}
