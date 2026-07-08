package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the embedded Spring Authorization Server (dev/test profiles only) exposes its OAuth2
 * metadata endpoint.
 *
 * <p>{@code spring.autoconfigure.exclude} disables {@code OAuth2ClientAutoConfiguration}: the
 * admin-UI OAuth2 client registered in T1 shares this JVM with the SAS it authenticates against, so
 * letting the client autoconfig build a {@code ClientRegistrationRepository} here would trigger
 * eager OIDC discovery against the (not-yet-listening) SAS during context refresh. This IT proves
 * the SAS in isolation; T4 exercises the full client+server handshake once both are wired together.
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
@TestPropertySource(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration")
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
}
