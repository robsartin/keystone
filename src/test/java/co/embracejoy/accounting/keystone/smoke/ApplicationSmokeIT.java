package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
@TestPropertySource(
    properties = {
      // Match TestSecurityConfig.ISSUER / AUDIENCE so JwtTestSupport's minted tokens
      // pass the JwtDecoder's iss + aud validators. Non-blank issuer flips
      // SecurityConfig into JWT-enforced mode.
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("Application smoke")
class ApplicationSmokeIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @LocalServerPort int port;
  @Autowired JwtTestSupport jwt;
  @Autowired JdbcClient jdbcClient;

  /**
   * Grants {@code smoke-user} the ADMIN role in the default tenant so every smoke call clears the
   * {@code @PreAuthorize} checks wired per the Q7 permission matrix. Inserted directly via JDBC
   * (bypassing {@code TenantUserRoleRepository}, which needs a request-scoped {@code TenantContext}
   * unavailable on this test thread under {@code RANDOM_PORT}); the Testcontainers superuser
   * connection bypasses {@code tenant_user_roles}' row-level security regardless.
   */
  @BeforeEach
  void grantSmokeUserAdminRole() {
    jdbcClient
        .sql(
            """
            INSERT INTO tenant_user_roles (tenant_id, user_sub, role, granted_at, granted_by)
            VALUES (:tenantId, :userSub, 'ADMIN', now(), 'system')
            ON CONFLICT (tenant_id, user_sub) DO NOTHING
            """)
        .param("tenantId", Tenants.DEFAULT_TENANT_UUID)
        .param("userSub", "smoke-user")
        .update();
  }

  /**
   * All smoke calls go through the fully-authenticated pipeline: a JWT with the default tenant
   * claim, matching the row inserted by V6.
   */
  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader(
            "Authorization", "Bearer " + jwt.mint("smoke-user", Tenants.DEFAULT_TENANT_ID))
        .build();
  }

  @Test
  @DisplayName("POST /journal-entries → 201 + counter increment visible at /actuator/prometheus")
  void shouldPostEntryAndIncrementMetric() {
    RestClient client = client();

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
    RestClient client = client();

    ResponseEntity<String> create = createAccount5000(client);

    assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(create.getHeaders().getLocation()).isNotNull();
  }

  @Test
  @DisplayName("POST /journal-entries debiting new EXPENSE account returns 201")
  void shouldPostEntryAgainstNewAccount() {
    RestClient client = client();
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
    RestClient client = client();

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
    RestClient client = client();

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
    RestClient client = client();
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
    RestClient client = client();

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

  @Test
  @DisplayName("Reversal round-trip: POST entry → POST reverse → GET both back with metadata")
  void shouldReverseAnEntryAndSurfaceMetadataOnBothSides() {
    RestClient client = client();

    // 1. Post a balanced original
    ResponseEntity<String> createOriginal = postReversalOriginal(client);
    assertThat(createOriginal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String originalId = idFromLocation(createOriginal.getHeaders().getLocation());

    // 2. Reverse it
    ResponseEntity<String> reverse = reverseEntry(client, originalId);
    assertThat(reverse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String reversalId = idFromLocation(reverse.getHeaders().getLocation());
    assertThat(reverse.getBody()).contains("\"reversesId\":\"" + originalId + "\"");
    assertThat(reverse.getBody()).contains("\"reversalReason\":\"smoke test reversal\"");

    // 3. GET the original — should now carry reversedBy metadata
    ResponseEntity<String> originalDetail = getEntry(client, originalId);
    assertThat(originalDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(originalDetail.getBody()).contains("\"reversedById\":\"" + reversalId + "\"");
    assertThat(originalDetail.getBody()).contains("\"reversedReason\":\"smoke test reversal\"");
  }

  private ResponseEntity<String> postReversalOriginal(RestClient client) {
    return client
        .post()
        .uri("/journal-entries")
        .contentType(MediaType.APPLICATION_JSON)
        .body(reversalOriginalBody())
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> reverseEntry(RestClient client, String originalId) {
    return client
        .post()
        .uri("/journal-entries/" + originalId + "/reverse")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{ \"reason\": \"smoke test reversal\" }")
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> getEntry(RestClient client, String id) {
    return client.get().uri("/journal-entries/" + id).retrieve().toEntity(String.class);
  }

  private static String reversalOriginalBody() {
    return """
        {
          "occurredOn": "2026-07-11",
          "description": "original for reversal smoke",
          "postings": [
            { "account": "1000", "side": "DEBIT",  "minorUnits": 4200,
              "currency": "USD", "baseMinorUnits": 4200 },
            { "account": "3000", "side": "CREDIT", "minorUnits": 4200,
              "currency": "USD", "baseMinorUnits": 4200 }
          ]
        }
        """;
  }

  @Test
  @DisplayName("Admin API: platform admin creates + lists tenants end-to-end")
  void shouldCreateAndListTenantsAsPlatformAdmin() {
    seedPlatformAdmin("smoke-platform-admin");
    RestClient client = platformAdminClient("smoke-platform-admin");

    ResponseEntity<String> create =
        client
            .post()
            .uri("/admin/tenants")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{ \"name\": \"Smoke Tenant\" }")
            .retrieve()
            .toEntity(String.class);
    assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(create.getHeaders().getLocation()).isNotNull();
    assertThat(create.getBody()).contains("\"name\":\"Smoke Tenant\"");

    ResponseEntity<String> list =
        client.get().uri("/admin/tenants").retrieve().toEntity(String.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).contains("\"name\":\"Smoke Tenant\"");
  }

  private void seedPlatformAdmin(String userSub) {
    jdbcClient
        .sql(
            "INSERT INTO platform_admins (user_sub, granted_at) VALUES (:userSub, now())"
                + " ON CONFLICT (user_sub) DO NOTHING")
        .param("userSub", userSub)
        .update();
  }

  private RestClient platformAdminClient(String userSub) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + jwt.mintWithoutTenant(userSub))
        .build();
  }

  private static String idFromLocation(URI location) {
    String path = location.getPath();
    // path is /journal-entries/<uuid> or /journal-entries/<uuid>/reverse — take segment after
    // /journal-entries/.
    int start = path.indexOf("/journal-entries/") + "/journal-entries/".length();
    int end = path.indexOf('/', start);
    return end == -1 ? path.substring(start) : path.substring(start, end);
  }
}
