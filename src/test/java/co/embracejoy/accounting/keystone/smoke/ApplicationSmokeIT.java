package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
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
}
