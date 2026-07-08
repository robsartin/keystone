package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.infrastructure.security.EmbeddedAuthorizationServerConfig;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Walks the full Authorization Code + PKCE handshake end-to-end: an unauthenticated request to a
 * protected {@code /admin/ui/**} resource, through the client's redirect into the embedded SAS, the
 * SAS's own {@code /login} form (T3.5), a credentialed POST, the authorization continuation, and
 * the app's callback endpoint completing the code exchange and establishing a session.
 *
 * <p>Pinned to {@link #TEST_PORT} (18080) via {@code DEFINED_PORT}, not the {@code
 * spring.security.oauth2.client.provider.keystone.*} default of 8080: {@code pom.xml}'s {@code
 * spring-boot-maven-plugin} boots a second, {@code local}-profile app instance bound to 8080 for
 * the whole {@code integration-test} phase (feeding the OpenAPI-snapshot plugin), so this IT's own
 * app can't bind 8080 too. 18080 is registered as an additional {@code redirect_uri} on the SAS's
 * {@code keystone-admin-ui} client (T3.6); the four {@code provider.keystone.*} endpoint URIs are
 * likewise overridden here so every outgoing client call (the authorize redirect, the token
 * exchange, the JWK fetch, the userinfo call) targets this test's own SAS instance on 18080 rather
 * than the unrelated 8080 instance.
 *
 * <p>Declares its own {@code @Container @ServiceConnection} Postgres, matching {@code
 * EmbeddedAuthorizationServerConfigIT}: {@code application-test.yaml}'s bare {@code jdbc:tc:} URL
 * would otherwise let Flyway and the app pool each independently spin up their own container.
 *
 * <p>Uses {@link RestClient} (not {@code TestRestTemplate}, moved out of core Boot 4) with a
 * hand-rolled {@link CookieJar}, matching the convention established in {@code
 * EmbeddedAuthorizationServerConfigIT} and {@code ApplicationSmokeIT}: neither {@code RestClient}
 * nor the JDK {@code HttpClient} it wraps follows redirects across manually-inspected {@code
 * Set-Cookie}/{@code Location} pairs, so each hop below is driven explicitly.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=" + OAuth2LoginFlowIT.TEST_PORT,
      "spring.security.oauth2.client.provider.keystone.authorization-uri="
          + "http://localhost:"
          + OAuth2LoginFlowIT.TEST_PORT
          + "/oauth2/authorize",
      "spring.security.oauth2.client.provider.keystone.token-uri="
          + "http://localhost:"
          + OAuth2LoginFlowIT.TEST_PORT
          + "/oauth2/token",
      "spring.security.oauth2.client.provider.keystone.jwk-set-uri="
          + "http://localhost:"
          + OAuth2LoginFlowIT.TEST_PORT
          + "/oauth2/jwks",
      "spring.security.oauth2.client.provider.keystone.user-info-uri="
          + "http://localhost:"
          + OAuth2LoginFlowIT.TEST_PORT
          + "/userinfo"
    })
@ActiveProfiles("test")
@Testcontainers
@DisplayName("OAuth2 login flow")
class OAuth2LoginFlowIT {

  static final int TEST_PORT = EmbeddedAuthorizationServerConfig.TEST_PORT;

  private static final Pattern CSRF_INPUT_PATTERN =
      Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  private final RestClient client =
      RestClient.builder().baseUrl("http://localhost:" + TEST_PORT).build();

  @Test
  @DisplayName("full Authorization Code + PKCE handshake ends with an authenticated session")
  void shouldCompleteAuthorizationCodeHandshake() {
    CookieJar cookies = new CookieJar();

    step1RequestsProtectedResource(cookies);
    URI authorizeUri = step2StartsAuthorizationRequest(cookies);
    step3AuthorizeRedirectsToLoginWhenUnauthenticated(authorizeUri, cookies);
    String csrfToken = step4FetchesLoginFormAndExtractsCsrf(cookies);
    URI authorizeContinuation = step5SubmitsCredentials(csrfToken, cookies);
    URI callbackUri = step6ResumesAuthorizationAfterLogin(authorizeContinuation, cookies);
    step7ExchangesCodeAndEstablishesSession(callbackUri, cookies);
  }

  private void step1RequestsProtectedResource(CookieJar cookies) {
    ResponseEntity<Void> response = get("/admin/ui/users", cookies, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation().toString())
        .contains("/oauth2/authorization/keystone");
    cookies.capture(response);
  }

  private URI step2StartsAuthorizationRequest(CookieJar cookies) {
    ResponseEntity<Void> response = get("/oauth2/authorization/keystone", cookies, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    URI authorizeUri = response.getHeaders().getLocation();
    assertThat(authorizeUri.getPath()).isEqualTo("/oauth2/authorize");
    assertThat(authorizeUri.getQuery()).contains("code_challenge=");
    cookies.capture(response);
    return authorizeUri;
  }

  private void step3AuthorizeRedirectsToLoginWhenUnauthenticated(
      URI authorizeUri, CookieJar cookies) {
    ResponseEntity<Void> response = get(authorizeUri, cookies, MediaType.TEXT_HTML);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/login");
    cookies.capture(response);
  }

  private String step4FetchesLoginFormAndExtractsCsrf(CookieJar cookies) {
    ResponseEntity<String> response =
        client
            .get()
            .uri("/login")
            .header(HttpHeaders.COOKIE, cookies.header())
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
            .exchange((req, resp) -> toEntity(resp, String.class));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    cookies.capture(response);
    Matcher matcher = CSRF_INPUT_PATTERN.matcher(response.getBody());
    assertThat(matcher.find()).as("CSRF token present in /login page").isTrue();
    return matcher.group(1);
  }

  private URI step5SubmitsCredentials(String csrfToken, CookieJar cookies) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("username", "admin@keystone.local");
    form.add("password", "demo");
    form.add("_csrf", csrfToken);
    ResponseEntity<Void> response =
        client
            .post()
            .uri("/login")
            .header(HttpHeaders.COOKIE, cookies.header())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .exchange((req, resp) -> toBodilessEntity(resp));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    URI location = response.getHeaders().getLocation();
    assertThat(location.toString()).contains("/oauth2/authorize");
    cookies.capture(response);
    return location;
  }

  private URI step6ResumesAuthorizationAfterLogin(URI authorizeContinuation, CookieJar cookies) {
    ResponseEntity<Void> response = get(authorizeContinuation, cookies, MediaType.TEXT_HTML);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    URI callbackUri = response.getHeaders().getLocation();
    assertThat(callbackUri.getPath()).isEqualTo("/login/oauth2/code/keystone");
    assertThat(callbackUri.getQuery()).contains("code=");
    cookies.capture(response);
    return callbackUri;
  }

  private void step7ExchangesCodeAndEstablishesSession(URI callbackUri, CookieJar cookies) {
    ResponseEntity<Void> response = get(callbackUri, cookies, null);
    assertThat(response.getStatusCode().is3xxRedirection()).isTrue();
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookies).isNotNull();
    assertThat(setCookies.stream().anyMatch(sc -> sc.startsWith("JSESSIONID="))).isTrue();
  }

  /** For relative paths with no query string — safe to let {@code RestClient} URI-encode. */
  private ResponseEntity<Void> get(String path, CookieJar cookies, MediaType accept) {
    return get(client.get().uri(path), cookies, accept);
  }

  /**
   * For absolute, already-encoded {@code Location} URIs extracted from a prior response. {@code
   * RestClient.uri(String)} would re-encode an already-percent-encoded query string (e.g. turning
   * {@code %3D} into {@code %253D}), corrupting the {@code state} and {@code scope} parameters the
   * SAS then rejects as {@code invalid_scope} — {@code uri(URI)} passes the pre-encoded {@link URI}
   * straight through, avoiding the double encoding.
   */
  private ResponseEntity<Void> get(URI uri, CookieJar cookies, MediaType accept) {
    return get(client.get().uri(uri), cookies, accept);
  }

  private ResponseEntity<Void> get(
      RestClient.RequestHeadersSpec<?> spec, CookieJar cookies, MediaType accept) {
    spec.header(HttpHeaders.COOKIE, cookies.header());
    if (accept != null) {
      spec.header(HttpHeaders.ACCEPT, accept.toString());
    }
    return spec.exchange((req, resp) -> toBodilessEntity(resp));
  }

  /**
   * {@link RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse} (unlike Boot 3's {@code
   * TestRestTemplate}-flavored client) exposes only {@code bodyTo}/{@code getStatusCode}/{@code
   * getHeaders} — no {@code toEntity}/{@code toBodilessEntity} convenience methods — so every
   * {@code exchange} call assembles the {@link ResponseEntity} itself via these two helpers.
   */
  private static <T> ResponseEntity<T> toEntity(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse resp, Class<T> bodyType)
      throws IOException {
    return ResponseEntity.status(resp.getStatusCode())
        .headers(resp.getHeaders())
        .body(resp.bodyTo(bodyType));
  }

  private static ResponseEntity<Void> toBodilessEntity(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse resp) throws IOException {
    return ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders()).build();
  }

  /** Accumulates {@code Set-Cookie} values across hops and replays them as a single header. */
  static class CookieJar {
    private final Map<String, String> cookies = new LinkedHashMap<>();

    void capture(ResponseEntity<?> response) {
      List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
      if (setCookies == null) {
        return;
      }
      for (String setCookie : setCookies) {
        int eq = setCookie.indexOf('=');
        int semi = setCookie.indexOf(';');
        String name = setCookie.substring(0, eq);
        String value = setCookie.substring(eq + 1, semi == -1 ? setCookie.length() : semi);
        cookies.put(name, value);
      }
    }

    String header() {
      StringBuilder sb = new StringBuilder();
      cookies.forEach(
          (name, value) -> {
            if (sb.length() > 0) {
              sb.append("; ");
            }
            sb.append(name).append('=').append(value);
          });
      return sb.toString();
    }
  }
}
