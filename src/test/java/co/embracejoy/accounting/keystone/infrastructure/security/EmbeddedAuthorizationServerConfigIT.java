package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the embedded Spring Authorization Server (dev/test profiles only) exposes its OAuth2
 * metadata endpoint.
 *
 * <p>No longer excludes {@code OAuth2ClientAutoConfiguration} (T2's original workaround for the
 * self-referential eager-discovery problem: the admin-UI OAuth2 client registered in T1 shares this
 * JVM with the SAS it authenticates against). T3's {@code application.yaml} now configures explicit
 * OAuth2 provider endpoint URIs (authorization/token/jwk-set/user-info) instead of an {@code
 * issuer-uri}, so building the {@code ClientRegistrationRepository} is pure property binding — no
 * discovery round-trip against the not-yet-listening SAS happens during context refresh. This IT
 * still proves the SAS in isolation (it never drives the client side of the handshake); T4
 * exercises the full client+server handshake now that both are wired together.
 *
 * <p>Uses {@link RestClient} (not {@code TestRestTemplate}, which Boot 4 moved to the {@code
 * spring-boot-resttestclient} module — not a project dependency) to match the convention already
 * established in {@code ApplicationSmokeIT}.
 *
 * <p>Declares its own {@code @Container @ServiceConnection} Postgres (matching every other IT in
 * this codebase) rather than relying on {@code application-test.yaml}'s bare {@code jdbc:tc:} URL:
 * Flyway (credentialed as {@code keystone}) and the app pool (credentialed as {@code test}) would
 * otherwise each independently trigger container creation, landing on two different, inconsistently
 * migrated containers. {@code @ServiceConnection} guarantees both consult the same one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("EmbeddedAuthorizationServerConfig")
class EmbeddedAuthorizationServerConfigIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @LocalServerPort int port;

  @Test
  @DisplayName("SAS metadata endpoint returns issuer + authorization + token endpoints")
  void shouldExposeMetadata() {
    RestClient rest = RestClient.builder().baseUrl("http://localhost:" + port).build();

    ResponseEntity<String> resp =
        rest.get().uri("/.well-known/oauth-authorization-server").retrieve().toEntity(String.class);

    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(resp.getBody()).contains("\"authorization_endpoint\"");
    assertThat(resp.getBody()).contains("\"token_endpoint\"");
    assertThat(resp.getBody()).contains("\"jwks_uri\"");
  }

  @Test
  @DisplayName("GET /login returns 200 with a form containing username, password, and CSRF fields")
  void shouldServeSasLoginPage() {
    RestClient rest = RestClient.builder().baseUrl("http://localhost:" + port).build();

    ResponseEntity<String> resp =
        rest.get().uri("/login").header("Accept", "text/html").retrieve().toEntity(String.class);

    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(resp.getBody())
        .contains("name=\"username\"")
        .contains("name=\"password\"")
        .contains("name=\"_csrf\"");
  }
}
