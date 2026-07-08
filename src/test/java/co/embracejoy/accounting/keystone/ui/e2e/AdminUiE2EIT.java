package co.embracejoy.accounting.keystone.ui.e2e;

import static co.embracejoy.accounting.keystone.ui.e2e.AxeAssertions.assertNoViolations;
import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.infrastructure.security.EmbeddedAuthorizationServerConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CLI;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives the admin UI end-to-end in a real headless Chromium browser (Playwright) and asserts zero
 * WCAG 2 A/AA violations (axe-core 4.10.2, vendored at {@code src/test/resources/axe.min.js}) on
 * every page state the four flows below visit.
 *
 * <p>Pinned to {@link EmbeddedAuthorizationServerConfig#TEST_PORT_E2E} (18081) via {@code
 * DEFINED_PORT} — distinct from {@code OAuth2LoginFlowIT}'s 18080 so the two full-context test
 * classes never contend for the same port, even though each runs in its own Surefire/Failsafe fork.
 * See that constant's javadoc and {@code EmbeddedAuthorizationServerConfig}'s {@code
 * registeredClientRepository()} for the matching {@code redirect_uri} registration. The four {@code
 * provider.keystone.*} endpoint overrides mirror {@code OAuth2LoginFlowIT} for the same reason: the
 * client must resolve every SAS endpoint against this test's own instance, not the unrelated
 * default-8080 one.
 *
 * <p>Declares its own {@code @Container @ServiceConnection} Postgres, matching every other
 * full-context IT in this codebase — {@code application-test.yaml}'s bare {@code jdbc:tc:} URL
 * would otherwise let Flyway and the app pool each independently spin up their own container.
 *
 * <p>Logs in via the real browser-facing flow rather than a shortcut: {@code /admin/ui/login} (our
 * own sign-in page, {@code permitAll}) &rarr; its "Sign in" link &rarr; {@code
 * /oauth2/authorization/keystone} &rarr; (unauthenticated) the embedded SAS's own default-generated
 * {@code /login} form &rarr; credentials &rarr; back through the authorization continuation and the
 * callback endpoint, landing on {@code UiSecurityConfig}'s {@code defaultSuccessUrl}, {@code
 * /admin/ui/users}. The three demo accounts and their tenant roles are seeded by {@link
 * co.embracejoy.accounting.keystone.infrastructure.security.DevUserSeeder} on {@code test} profile
 * startup — no per-test JDBC seeding needed.
 *
 * <p>The bookkeeper-forbidden flow asserts on a {@code [role=alert]} locator rather than {@code
 * #alert-region}: {@code GET /admin/ui/tenants} is a plain browser navigation (not an HTMX
 * request), so {@code UiExceptionHandler#onAccessDenied} renders bare {@code fragments/alert ::
 * alert} markup as the entire response body — {@code #alert-region} lives in {@code layout.html},
 * which this response never passes through. htmx's own default {@code responseHandling} config
 * (v2.0.4, see {@code htmx.min.js}) doesn't swap 4xx/5xx bodies into any target either, so {@code
 * #alert-region} is not currently wired to receive alerts from any of this suite's four flows.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=" + AdminUiE2EIT.PORT,
      "spring.security.oauth2.client.provider.keystone.authorization-uri="
          + "http://localhost:"
          + AdminUiE2EIT.PORT
          + "/oauth2/authorize",
      "spring.security.oauth2.client.provider.keystone.token-uri="
          + "http://localhost:"
          + AdminUiE2EIT.PORT
          + "/oauth2/token",
      "spring.security.oauth2.client.provider.keystone.jwk-set-uri="
          + "http://localhost:"
          + AdminUiE2EIT.PORT
          + "/oauth2/jwks",
      "spring.security.oauth2.client.provider.keystone.user-info-uri="
          + "http://localhost:"
          + AdminUiE2EIT.PORT
          + "/userinfo"
    })
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("e2e")
@DisplayName("AdminUiE2E")
class AdminUiE2EIT {

  static final int PORT = EmbeddedAuthorizationServerConfig.TEST_PORT_E2E;

  private static final String BASE_URL = "http://localhost:" + PORT;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  private static Playwright playwright;
  private static Browser browser;

  private BrowserContext context;
  private Page page;

  @BeforeAll
  void installAndLaunchBrowser() throws Exception {
    installChromiumBrowser();
    playwright = Playwright.create();
    browser =
        playwright
            .chromium()
            .launch(new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(false));
  }

  @AfterAll
  void stopBrowser() {
    browser.close();
    playwright.close();
  }

  /**
   * {@link CLI#main} always calls {@code System.exit} once the child driver process finishes — fine
   * for its intended standalone-tool use, fatal here: called in-process, it kills Surefire's own
   * forked JVM before any {@code @Test} runs. Runs it in a genuine child JVM instead, inheriting
   * this fork's own classpath, so only that child process exits.
   */
  private static void installChromiumBrowser() throws IOException, InterruptedException {
    String javaBin =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    ProcessBuilder pb =
        new ProcessBuilder(
            javaBin,
            "-cp",
            System.getProperty("java.class.path"),
            CLI.class.getName(),
            "install",
            "chromium");
    pb.inheritIO();
    int exitCode = pb.start().waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("Playwright chromium install failed, exit code " + exitCode);
    }
  }

  @BeforeEach
  void freshContext() {
    context = browser.newContext();
    page = context.newPage();
    page.onDialog(Dialog::accept);
  }

  @AfterEach
  void closeContext() {
    context.close();
  }

  @Test
  @DisplayName("platform admin creates + lists + deactivates a tenant, WCAG AA clean")
  void platformAdminTenantCrud() throws Exception {
    login("platform@keystone.local");
    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Tenants")).click();
    assertNoViolations(page);

    String tenantName = "Acme E2E " + System.nanoTime();
    page.getByLabel("Tenant name").fill(tenantName);
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create tenant")).click();

    Locator row = tableRow(tenantName);
    row.waitFor();
    assertNoViolations(page);

    row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Deactivate")).click();
    row.getByText("Deactivated").waitFor();
    assertThat(row.innerText()).contains("Deactivated");
    assertNoViolations(page);
  }

  @Test
  @DisplayName("tenant admin adds + changes + removes user, WCAG AA clean")
  void tenantAdminUserCrud() throws Exception {
    login("admin@keystone.local");
    assertNoViolations(page);

    // Scoped by id, not label text: per-row selects now carry an "Role for <sub>" aria-label
    // (T11's select-name a11y fix) whose text also substring-matches a plain getByLabel("Role").
    page.getByLabel("User sub").fill("auth0|bob");
    page.locator("#addRole").selectOption("BOOKKEEPER");
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add user")).click();

    Locator row = tableRow("auth0|bob");
    row.waitFor();
    assertThat(row.innerText()).contains("BOOKKEEPER");
    assertNoViolations(page);

    changeRoleAndAwaitPersist(row, "ADMIN");
    assertNoViolations(page);

    row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove")).click();
    row.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
    assertThat(row.count()).isEqualTo(0);
    assertNoViolations(page);
  }

  @Test
  @DisplayName("bookkeeper hitting /admin/ui/tenants sees a 403 alert, WCAG AA clean")
  void bookkeeperSeesForbidden() throws Exception {
    login("bookkeeper@keystone.local");
    page.navigate(BASE_URL + "/admin/ui/tenants");
    assertThat(page.getByRole(AriaRole.ALERT).innerText()).contains("Not allowed");
    assertNoViolations(page);
  }

  @Test
  @DisplayName("log out returns to the sign-in page, WCAG AA clean")
  void logoutReturnsToLogin() throws Exception {
    login("admin@keystone.local");
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log out")).click();
    assertThat(page.url()).endsWith("/admin/ui/login");
    assertNoViolations(page);
  }

  /**
   * Walks the real browser-facing login flow: our sign-in page's link into the SAS's own
   * default-generated {@code /login} form, then credentials. See the class javadoc for the full hop
   * sequence.
   */
  private void login(String username) {
    page.navigate(BASE_URL + "/admin/ui/login");
    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Sign in")).click();
    page.getByLabel("Username").fill(username);
    page.getByLabel("Password").fill("demo");
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();
  }

  private Locator tableRow(String text) {
    return page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(text));
  }

  /**
   * Selects {@code role} on {@code row}'s HTMX-wired {@code <select>} and blocks until the {@code
   * PUT} it triggers round-trips, so the following assertion observes server-confirmed state (an
   * immediate {@code inputValue()} read would only prove the browser's own optimistic DOM update).
   */
  private void changeRoleAndAwaitPersist(Locator row, String role) {
    Locator select = row.locator("select");
    page.waitForResponse(
        resp -> resp.url().contains("/admin/ui/users/") && "PUT".equals(resp.request().method()),
        () -> select.selectOption(role));
    assertThat(row.locator("select").inputValue()).isEqualTo(role);
  }
}
