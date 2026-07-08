# Slice 5 Phase D-admin-ui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the server-rendered admin UI (`/admin/ui/*`) for tenant + user-role management, closing the Slice 5 UI surface.

**Architecture:** Second `SecurityFilterChain` handling `/admin/ui/**` with OAuth2 Login (Authorization Code + PKCE) → `HttpSession` cookie. Embedded Spring Authorization Server on `dev`/`test` profiles so local dev + tests run without an external IdP. Thymeleaf renders full pages and named fragments; HTMX 2.x drives in-place row mutations. Vendored Bootstrap CSS + Icons + HTMX — no Node.js, no build step. Same `TenantContext` + application services as the API surface.

**Tech Stack:** Spring Boot 4.0.3, Java 25, Spring Security 6, `spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`, `spring-security-oauth2-authorization-server`, HTMX 2.x (vendored), Bootstrap 5 (vendored), Playwright Java, axe-core (vendored).

## Global Constraints

- **Java 25**, Spring Boot 4.0.3, Maven wrapper `./mvnw`.
- **Hexagonal layering** (ADR-0002, ArchUnit-enforced): `domain` → nothing else; `application` → `domain` only; `infrastructure` → both. UI code lives in `infrastructure.web.ui.*`.
- **TDD always:** red → green → refactor → commit. Every task starts with a failing test.
- **Google Java Format** via Spotless (`./mvnw spotless:apply`).
- **Checkstyle:** 750-line file max, 30-line method max, no star imports, braces required.
- **JaCoCo gate:** 85% line coverage on `domain..` + `application..`. UI code is exempt (no domain logic), but the gate must not regress.
- **Tests use `@DisplayName`** and method names `should<Expected>When<Condition>`.
- **`Result<T, E>` for internal errors, not exceptions** (ADR-0004). At the HTTP boundary, `UiResultMapper` (delegating to existing `ResultMapper.toProblemDetail`) produces `AlertView` records for HTMX fragments.
- **No `@RestController` for UI controllers** — they return view names or fragment names (SpringDoc scans `@RestController` only, so the OpenAPI snapshot is untouched).
- **Vendored static assets only** (ADR-0021): no `package.json`, no `node_modules`, no npm/bun/webpack. Bootstrap CSS + HTMX + axe.min.js live in `src/main/resources/static/` (prod) or `src/test/resources/` (test tool).
- **Every ADR in this slice** (0019–0022) includes an "Enforcement" section per ADR-0018 — either citing an ArchUnit rule or stating why the rule is non-code-structural.
- **PR-based workflow:** work commits to branch `16-slice-5-phase-d-admin-ui`. Never commit direct to `main`.

---

## Files

### Create (production code)

| Path | Responsibility |
|---|---|
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/UiSecurityConfig.java` | Second `SecurityFilterChain` for `/admin/ui/**`; oauth2Login; session; CSRF cookie |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/AuthenticationTenantResolver.java` | Given a Spring `Authentication`, extract sub + tenant claim and populate `TenantContext` + authorities. Works for both `JwtAuthenticationToken` (API) and `OAuth2AuthenticationToken` (UI). |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/EmbeddedAuthorizationServerConfig.java` | `@Configuration @Profile({"dev","test"})` — SAS beans, single registered client `keystone-admin-ui`, in-memory 3-user `UserDetailsService`, RSA JWK, `OAuth2TokenCustomizer` that copies tenant claim. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/DevUserSeeder.java` | `@Component @Profile({"dev","test"})` `ApplicationRunner` + `@ConditionalOnProperty("keystone.dev.seed-users", matchIfMissing=true)`. Idempotent `INSERT ... ON CONFLICT DO NOTHING` on `platform_admins` + `tenant_user_roles` for the 3 seeded users. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/HtmxAuthenticationEntryPoint.java` | If `HX-Request: true`, respond 200 with `HX-Redirect: /admin/ui/login` header. Else defer to Spring's `LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keystone")`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiController.java` | `GET /admin/ui` → 302 `/admin/ui/users` |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiController.java` | `GET /admin/ui/login` — permits anonymous; renders `login.html` with a "Sign in" link to `/oauth2/authorization/keystone`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiController.java` | `GET /admin/ui/users` list, `POST /admin/ui/users` add (HTMX prepend), `PUT /admin/ui/users/{sub}` change role (HTMX swap-row), `DELETE /admin/ui/users/{sub}` revoke (HTMX fade-out). `@PreAuthorize("hasRole('ADMIN')")`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiController.java` | `GET /admin/ui/tenants` list, `POST /admin/ui/tenants` create (HTMX prepend), `GET /admin/ui/tenants/{id}` detail, `POST /admin/ui/tenants/{id}/deactivate` soft-delete. `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ProfileUiController.java` | `GET /admin/ui/profile` — read-only render of current sub, tenant, role. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UiResultMapper.java` | `AlertView.from(TenantError|SecurityError)` — delegates to existing `ResultMapper.toProblemDetail` and lifts title/detail; picks HTTP status per error. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UiExceptionHandler.java` | `@ControllerAdvice(basePackages = "…web.ui")` — maps `MethodArgumentNotValidException` and `AccessDeniedException` to the shared `fragments/alert` view with the right status. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/AddUserForm.java` | Record `(String userSub, String role)` with `@NotBlank @Size(max=200)` on `userSub`; `@Pattern("^(ADMIN|BOOKKEEPER|READ_ONLY)$")` on `role`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/ChangeRoleForm.java` | Record `(String role)` — same `@Pattern`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/CreateTenantForm.java` | Record `(String name)` with `@NotBlank @Size(max=200)`. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/AlertView.java` | Record `(String severity, String title, String detail)` where `severity ∈ {"info","warning","danger"}`. |
| `src/main/resources/templates/layout.html` | Shared `<head>` + top nav + `<div id="alert-region" role="status" aria-live="polite">` + main slot. Injects CSRF meta + HTMX config script. |
| `src/main/resources/templates/login.html` | Anonymous page with "Sign in" button. |
| `src/main/resources/templates/users.html` | Full page: add-user form at top + user table; each row is an included `fragments/user-row.html`. Empty state via `<tbody th:if="${users.isEmpty()}">`. |
| `src/main/resources/templates/tenants.html` | Full page: create-tenant form + tenant table. |
| `src/main/resources/templates/tenant-detail.html` | Tenant detail + Deactivate button. |
| `src/main/resources/templates/profile.html` | Read-only sub / tenant / role card. |
| `src/main/resources/templates/fragments/user-row.html` | One `<tr>` — the target of HTMX PUT/DELETE swaps. Includes role dropdown + Remove button. |
| `src/main/resources/templates/fragments/tenant-row.html` | One `<tr>` — the target of HTMX POST/deactivate swaps. |
| `src/main/resources/templates/fragments/alert.html` | `<div class="alert alert-{{severity}}">…` — target of `#alert-region`. |
| `src/main/resources/static/bootstrap.min.css` | Vendored Bootstrap 5.3.x CSS. |
| `src/main/resources/static/bootstrap-icons.css` | Vendored Bootstrap Icons CSS. |
| `src/main/resources/static/bootstrap-icons.woff2` | Vendored icon font. |
| `src/main/resources/static/htmx.min.js` | Vendored HTMX 2.x. |
| `src/main/resources/static/keystone-admin.css` | Small overrides: focus-visible bump, no-fade on `.htmx-swapping`. |
| `docs/adr/0019-oauth2-client-and-session-for-ui.md` | ADR — OAuth2 Login + session cookie for UI. Enforcement: ArchUnit `OAUTH2LOGIN_ONLY_IN_UI_SECURITY_CONFIG`. |
| `docs/adr/0020-embedded-authorization-server-for-dev-and-test.md` | ADR — SAS on `dev`/`test`. Enforcement: ArchUnit `SAS_CONFIG_IS_PROFILE_GUARDED`. |
| `docs/adr/0021-server-rendered-ui-thymeleaf-htmx-no-build.md` | ADR — Thymeleaf + HTMX, no JS build. Enforcement: repo-root check that no `package.json` / `node_modules` exist (build script). |
| `docs/adr/0022-playwright-and-axe-core-ci-gate.md` | ADR — Playwright + axe as CI gate. Enforcement: `AdminUiE2ETest` runs in `verify`. |

### Create (test code)

| Path | Responsibility |
|---|---|
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiControllerTest.java` | Redirect assertion. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiControllerTest.java` | Anonymous render, correct link href, no auth required. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiControllerTest.java` | List, add, change, remove, 403 for BOOKKEEPER, 401 for anon, HTMX headers. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiControllerTest.java` | List, create, detail, deactivate, 403 for ADMIN (non-platform), HTMX headers. |
| `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ProfileUiControllerTest.java` | Renders sub / tenant / role. |
| `src/test/java/co/embracejoy/accounting/keystone/smoke/OAuth2LoginFlowIT.java` | `@SpringBootTest` — full Authorization Code + PKCE handshake via `TestRestTemplate`. |
| `src/test/java/co/embracejoy/accounting/keystone/ui/e2e/AdminUiE2ETest.java` | Playwright + axe — 4 flows (§6.3 of spec). |
| `src/test/java/co/embracejoy/accounting/keystone/ui/e2e/AxeAssertions.java` | Helper: given a Playwright `Page`, inject `axe.min.js`, run `axe.run()`, assert zero WCAG AA violations. |
| `src/test/java/co/embracejoy/accounting/keystone/architecture/UiSecurityArchTest.java` | ArchUnit rules for ADR-0019 + ADR-0020. |
| `src/test/resources/axe.min.js` | Vendored axe-core 4.x. |

### Modify

| Path | What changes |
|---|---|
| `pom.xml` | Add: `spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`, `spring-security-oauth2-authorization-server`, `com.microsoft.playwright:playwright` (test scope). |
| `src/main/resources/application.yaml` | Add `spring.profiles.active: dev` (dev only), `keystone.dev.seed-users: true`, add SAS client registration under `spring.security.oauth2.client.registration.keystone`. |
| `docs/adr/README.md` | Index rows for ADRs 0019–0022. |
| `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/SecurityConfig.java` | Add `@Order(2)` on the existing API filter chain (T3 adds `@Order(1)` on UI chain). No behavioral change; ordering is now explicit. |

---

## Tasks

### Task 1: Dependencies + application.yaml

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`

**Interfaces:**
- Consumes: nothing (starts the branch).
- Produces: Thymeleaf, OAuth2 client, Spring Authorization Server, Playwright on the classpath. `keystone.dev.seed-users` property readable. SAS client registration `keystone` under `spring.security.oauth2.client.registration`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/dependencies/UiDependencyPresenceTest.java`:

```java
package co.embracejoy.accounting.keystone.dependencies;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UI dependencies")
class UiDependencyPresenceTest {

  @Test
  @DisplayName("thymeleaf ClassLoader.loadClass resolves TemplateEngine")
  void shouldResolveThymeleafTemplateEngine() {
    assertThatCode(() -> Class.forName("org.thymeleaf.TemplateEngine")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("spring-security-oauth2-client resolves OAuth2LoginConfigurer")
  void shouldResolveOAuth2LoginConfigurer() {
    assertThatCode(
            () ->
                Class.forName(
                    "org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("spring-security-oauth2-authorization-server resolves AuthorizationServerSettings")
  void shouldResolveAuthorizationServerSettings() {
    assertThatCode(
            () ->
                Class.forName(
                    "org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("playwright resolves Playwright entry point")
  void shouldResolvePlaywright() {
    assertThatCode(() -> Class.forName("com.microsoft.playwright.Playwright"))
        .doesNotThrowAnyException();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw -B test -Dtest=UiDependencyPresenceTest`
Expected: FAIL — all 4 tests throw `ClassNotFoundException`.

- [ ] **Step 3: Add dependencies to `pom.xml`**

Under `<dependencies>` (production scope, after the existing `spring-boot-starter-oauth2-resource-server`):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-oauth2-authorization-server</artifactId>
</dependency>
```

Under `<dependencies>` (test scope, after `spring-boot-starter-test`):

```xml
<dependency>
  <groupId>com.microsoft.playwright</groupId>
  <artifactId>playwright</artifactId>
  <version>1.49.0</version>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -B test -Dtest=UiDependencyPresenceTest`
Expected: PASS — 4 tests green.

- [ ] **Step 5: Update `application.yaml`**

Append to `src/main/resources/application.yaml`:

```yaml
keystone:
  dev:
    seed-users: ${KEYSTONE_DEV_SEED_USERS:true}

spring:
  security:
    oauth2:
      client:
        registration:
          keystone:
            provider: keystone
            client-id: keystone-admin-ui
            client-authentication-method: none
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile
        provider:
          keystone:
            issuer-uri: ${KEYSTONE_ISSUER_URI:http://localhost:8080}
```

- [ ] **Step 6: Full verify**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS (unit tests green; no new IT yet).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/test/java/co/embracejoy/accounting/keystone/dependencies/UiDependencyPresenceTest.java
git commit -m "Slice 5 Phase D-admin-ui T1: add Thymeleaf, oauth2-client, authorization-server, playwright deps"
```

---

### Task 2: Embedded Spring Authorization Server + DevUserSeeder

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/EmbeddedAuthorizationServerConfig.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/DevUserSeeder.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/EmbeddedAuthorizationServerConfigIT.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/DevUserSeederIT.java`

**Interfaces:**
- Consumes: Spring Authorization Server API from T1.
- Produces: `AuthorizationServerSettings` bean, `RegisteredClientRepository` bean (single client `keystone-admin-ui`), `UserDetailsService` bean with 3 users, `OAuth2TokenCustomizer<JwtEncodingContext>` that copies tenant claim into ID token. `DevUserSeeder` inserts 3 rows into `platform_admins` + `tenant_user_roles` idempotently.

- [ ] **Step 1: Write the failing SAS IT**

Create `EmbeddedAuthorizationServerConfigIT.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("EmbeddedAuthorizationServerConfig")
class EmbeddedAuthorizationServerConfigIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;

  @Test
  @DisplayName("SAS metadata endpoint returns issuer + authorization + token endpoints")
  void shouldExposeMetadata() {
    ResponseEntity<String> resp =
        rest.getForEntity(
            "http://localhost:" + port + "/.well-known/oauth-authorization-server", String.class);

    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(resp.getBody()).contains("\"authorization_endpoint\"");
    assertThat(resp.getBody()).contains("\"token_endpoint\"");
    assertThat(resp.getBody()).contains("\"jwks_uri\"");
  }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B verify -Dtest=EmbeddedAuthorizationServerConfigIT -Dit.test=none`
Expected: FAIL — no SAS beans, endpoint returns 404 or app fails to start.

- [ ] **Step 3: Write `EmbeddedAuthorizationServerConfig`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
@Profile({"dev", "test"})
public class EmbeddedAuthorizationServerConfig {

  static final String TENANT_CLAIM = "https://keystone.embracejoy.co/tenant_id";
  static final String DEFAULT_TENANT_ID = "01902f9f-0000-7000-8000-00000000d1f1";

  static final Map<String, String> USER_TENANTS =
      Map.of(
          "sas|platform", DEFAULT_TENANT_ID,
          "sas|admin", DEFAULT_TENANT_ID,
          "sas|bookkeeper", DEFAULT_TENANT_ID);

  @Bean
  public AuthorizationServerSettings authorizationServerSettings() {
    return AuthorizationServerSettings.builder().build();
  }

  @Bean
  public RegisteredClientRepository registeredClientRepository() {
    RegisteredClient adminUi =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("keystone-admin-ui")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8080/login/oauth2/code/keystone")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).build())
            .build();
    return new InMemoryRegisteredClientRepository(adminUi);
  }

  @Bean
  public UserDetailsService sasUsers() {
    return new InMemoryUserDetailsManager(
        List.of(
            User.withUsername("platform@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build(),
            User.withUsername("admin@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build(),
            User.withUsername("bookkeeper@keystone.local")
                .password("{noop}demo")
                .authorities("ROLE_USER")
                .build()));
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair kp = kpg.generateKeyPair();
    RSAKey rsa =
        new RSAKey.Builder((RSAPublicKey) kp.getPublic())
            .privateKey((RSAPrivateKey) kp.getPrivate())
            .keyID(UUID.randomUUID().toString())
            .build();
    return new ImmutableJWKSet<>(new JWKSet(rsa));
  }

  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> tenantClaimCustomizer() {
    return context -> {
      String username = context.getPrincipal().getName();
      String sub =
          switch (username) {
            case "platform@keystone.local" -> "sas|platform";
            case "admin@keystone.local" -> "sas|admin";
            case "bookkeeper@keystone.local" -> "sas|bookkeeper";
            default -> username;
          };
      context.getClaims().subject(sub);
      String tenant = USER_TENANTS.get(sub);
      if (tenant != null) {
        context.getClaims().claim(TENANT_CLAIM, tenant);
      }
    };
  }
}
```

- [ ] **Step 4: Register SAS filter chain**

Append to `EmbeddedAuthorizationServerConfig`:

```java
  @Bean
  @Order(0)
  public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer configurer =
        OAuth2AuthorizationServerConfigurer.authorizationServer();
    http.securityMatcher(configurer.getEndpointsMatcher())
        .with(configurer, c -> c.oidc(Customizer.withDefaults()))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .csrf(c -> c.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
        .formLogin(Customizer.withDefaults());
    return http.build();
  }
```

(Add matching imports.)

- [ ] **Step 5: Run to verify SAS IT PASSES**

Run: `./mvnw -B verify -Dit.test=EmbeddedAuthorizationServerConfigIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 6: Write the failing DevUserSeeder IT**

Create `DevUserSeederIT.java`:

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("DevUserSeeder")
class DevUserSeederIT {

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("seeds sas|platform into platform_admins")
  void shouldSeedPlatformAdmin() {
    Long count =
        jdbc.sql("SELECT count(*) FROM platform_admins WHERE user_sub = 'sas|platform'")
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("seeds three rows into tenant_user_roles")
  void shouldSeedTenantUserRoles() {
    Long count =
        jdbc.sql("SELECT count(*) FROM tenant_user_roles WHERE user_sub LIKE 'sas|%'")
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(3L);
  }
}
```

- [ ] **Step 7: Run to verify FAIL**

Run: `./mvnw -B verify -Dit.test=DevUserSeederIT -DfailIfNoTests=false`
Expected: FAIL — no seeder yet, tables empty.

- [ ] **Step 8: Write `DevUserSeeder`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "keystone.dev.seed-users", havingValue = "true", matchIfMissing = true)
public class DevUserSeeder implements ApplicationRunner {

  private static final UUID DEFAULT_TENANT =
      UUID.fromString(EmbeddedAuthorizationServerConfig.DEFAULT_TENANT_ID);

  private final JdbcClient jdbc;
  private final Clock clock;

  public DevUserSeeder(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    jdbc.sql(
            "INSERT INTO platform_admins (user_sub, granted_at, granted_by) "
                + "VALUES ('sas|platform', ?, 'system') ON CONFLICT DO NOTHING")
        .param(clock.instant())
        .update();

    for (var e :
        java.util.Map.of(
                "sas|platform", "ADMIN",
                "sas|admin", "ADMIN",
                "sas|bookkeeper", "BOOKKEEPER")
            .entrySet()) {
      jdbc.sql(
              "INSERT INTO tenant_user_roles (tenant_id, user_sub, role, granted_at, granted_by) "
                  + "VALUES (?, ?, ?, ?, 'system') ON CONFLICT DO NOTHING")
          .param(DEFAULT_TENANT)
          .param(e.getKey())
          .param(e.getValue())
          .param(clock.instant())
          .update();
    }
  }
}
```

- [ ] **Step 9: Run to verify DevUserSeeder IT PASSES**

Run: `./mvnw -B verify -Dit.test=DevUserSeederIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/EmbeddedAuthorizationServerConfig.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/DevUserSeeder.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/EmbeddedAuthorizationServerConfigIT.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/DevUserSeederIT.java
git commit -m "Slice 5 Phase D-admin-ui T2: embedded SAS + DevUserSeeder on dev/test profiles"
```

---

### Task 3: UiSecurityConfig + AuthenticationTenantResolver + HtmxAuthenticationEntryPoint

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/UiSecurityConfig.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/AuthenticationTenantResolver.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/HtmxAuthenticationEntryPoint.java`
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/SecurityConfig.java` — add `@Order(2)` on existing filter chain bean.
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/HtmxAuthenticationEntryPointTest.java`

**Interfaces:**
- Consumes: `TenantContext`, `TenantRepository`, `TenantUserRoleRepository`, `PlatformAdminRepository` (all Phase A). SAS registered client `keystone` (T1 yaml + T2 SAS beans).
- Produces: `@Order(1)` UI `SecurityFilterChain` matching `/admin/ui/**` with `oauth2Login`, session cookie, CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()`, entry point that emits `HX-Redirect` for HTMX requests.

- [ ] **Step 1: Write the failing entry-point unit test**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

@DisplayName("HtmxAuthenticationEntryPoint")
class HtmxAuthenticationEntryPointTest {

  private final AuthenticationEntryPoint fallback = Mockito.mock(AuthenticationEntryPoint.class);
  private final HtmxAuthenticationEntryPoint entryPoint =
      new HtmxAuthenticationEntryPoint("/admin/ui/login", fallback);

  @Test
  @DisplayName("emits HX-Redirect header when request has HX-Request: true")
  void shouldEmitHxRedirect() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    req.addHeader("HX-Request", "true");
    MockHttpServletResponse resp = new MockHttpServletResponse();

    entryPoint.commence(req, resp, new InsufficientAuthenticationException("nope"));

    assertThat(resp.getStatus()).isEqualTo(200);
    assertThat(resp.getHeader("HX-Redirect")).isEqualTo("/admin/ui/login");
    Mockito.verifyNoInteractions(fallback);
  }

  @Test
  @DisplayName("delegates to fallback when no HX-Request header")
  void shouldDelegateWhenNotHtmx() throws Exception {
    HttpServletRequest req = new MockHttpServletRequest("GET", "/admin/ui/users");
    HttpServletResponse resp = new MockHttpServletResponse();

    entryPoint.commence(req, resp, new InsufficientAuthenticationException("nope"));

    Mockito.verify(fallback).commence(Mockito.eq(req), Mockito.eq(resp), Mockito.any());
  }
}
```

- [ ] **Step 2: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=HtmxAuthenticationEntryPointTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Write `HtmxAuthenticationEntryPoint`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public class HtmxAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final String loginPath;
  private final AuthenticationEntryPoint fallback;

  public HtmxAuthenticationEntryPoint(String loginPath, AuthenticationEntryPoint fallback) {
    this.loginPath = loginPath;
    this.fallback = fallback;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, jakarta.servlet.ServletException {
    if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
      response.setStatus(200);
      response.setHeader("HX-Redirect", loginPath);
      return;
    }
    fallback.commence(request, response, exception);
  }
}
```

- [ ] **Step 4: Run to verify unit test PASSES**

Run: `./mvnw -B test -Dtest=HtmxAuthenticationEntryPointTest`
Expected: PASS.

- [ ] **Step 5: Write `AuthenticationTenantResolver`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationTenantResolver {

  private final KeystoneSecurityProperties props;
  private final TenantRepository tenants;
  private final TenantUserRoleRepository roles;
  private final PlatformAdminRepository platformAdmins;
  private final TenantContext tenantContext;

  public AuthenticationTenantResolver(
      KeystoneSecurityProperties props,
      TenantRepository tenants,
      TenantUserRoleRepository roles,
      PlatformAdminRepository platformAdmins,
      TenantContext tenantContext) {
    this.props = props;
    this.tenants = tenants;
    this.roles = roles;
    this.platformAdmins = platformAdmins;
    this.tenantContext = tenantContext;
  }

  public Collection<GrantedAuthority> resolve(OAuth2User user) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    String sub = subFrom(user);
    if (platformAdmins.exists(sub)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }
    Object claim = user.getAttribute(props.tenantClaim());
    if (claim != null) {
      TenantId tenantId = parseTenantId(claim.toString());
      if (tenants.findById(tenantId).isEmpty()) {
        throw new InvalidBearerTokenException("unknown tenant");
      }
      tenantContext.set(tenantId);
      roles
          .findRole(tenantId, sub)
          .ifPresent(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.role().name())));
    }
    return authorities;
  }

  private static String subFrom(OAuth2User user) {
    if (user instanceof OidcUser oidc) {
      return oidc.getSubject();
    }
    Object sub = user.getAttribute("sub");
    return sub == null ? user.getName() : sub.toString();
  }

  private static TenantId parseTenantId(String claim) {
    try {
      return new TenantId(UUID.fromString(claim));
    } catch (IllegalArgumentException e) {
      throw new InvalidBearerTokenException("invalid tenant claim");
    }
  }
}
```

- [ ] **Step 6: Write `UiSecurityConfig`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class UiSecurityConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain uiFilterChain(
      HttpSecurity http, AuthenticationTenantResolver tenantResolver) throws Exception {
    OidcUserService delegate = new OidcUserService();
    var loginEntry = new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keystone");
    var htmxEntry = new HtmxAuthenticationEntryPoint("/admin/ui/login", loginEntry);

    http.securityMatcher("/admin/ui/**", "/oauth2/authorization/**", "/login/oauth2/code/**")
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/admin/ui/login").permitAll().anyRequest().authenticated())
        .oauth2Login(
            o ->
                o.userInfoEndpoint(
                        u ->
                            u.oidcUserService(
                                req -> {
                                  var user = delegate.loadUser(req);
                                  var authorities = tenantResolver.resolve(user);
                                  return new DefaultOidcUser(
                                      authorities, user.getIdToken(), user.getUserInfo(), "sub");
                                }))
                    .defaultSuccessUrl("/admin/ui/users", true))
        .logout(l -> l.logoutSuccessUrl("/admin/ui/login").permitAll())
        .exceptionHandling(e -> e.authenticationEntryPoint(htmxEntry))
        .csrf(
            c ->
                c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

    return http.build();
  }
}
```

- [ ] **Step 7: Add `@Order(2)` to existing `SecurityConfig` filter chain**

Open `SecurityConfig.java`. Locate `@Bean public SecurityFilterChain filterChain(...)`. Add `@Order(2)` above `@Bean`. No other changes.

- [ ] **Step 8: Full verify**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/UiSecurityConfig.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/AuthenticationTenantResolver.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/HtmxAuthenticationEntryPoint.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/SecurityConfig.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/security/HtmxAuthenticationEntryPointTest.java
git commit -m "Slice 5 Phase D-admin-ui T3: UiSecurityConfig + AuthenticationTenantResolver + HtmxAuthenticationEntryPoint"
```

---

### Task 4: OAuth2LoginFlowIT — full handshake

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/smoke/OAuth2LoginFlowIT.java`

**Interfaces:**
- Consumes: T2 SAS + T3 UI security chain.
- Produces: proof that a headless HTTP client walking the redirect chain (302 → SAS login form → POST creds → 302 back with code → app exchanges code) ends up with a valid session cookie and can hit an authenticated resource.

- [ ] **Step 1: Write the failing test**

Full test in one file:

```java
package co.embracejoy.accounting.keystone.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("OAuth2 login flow")
class OAuth2LoginFlowIT {

  @LocalServerPort int port;

  @Test
  @DisplayName("full Authorization Code + PKCE handshake ends with authenticated session")
  void shouldCompleteAuthorizationCodeHandshake() {
    String base = "http://localhost:" + port;
    RestClient client = RestClient.builder().baseUrl(base).build();
    CookieJar cookies = new CookieJar();

    // 1. GET /admin/ui/users → 302 /oauth2/authorization/keystone
    ResponseEntity<Void> step1 =
        client.get().uri("/admin/ui/users").exchange((req, resp) -> resp.toBodilessEntity());
    assertThat(step1.getStatusCode().is3xxRedirection()).isTrue();
    assertThat(step1.getHeaders().getLocation().toString()).contains("/oauth2/authorization/keystone");
    cookies.capture(step1);

    // 2. GET /oauth2/authorization/keystone → 302 /oauth2/authorize?…
    ResponseEntity<Void> step2 =
        client
            .get()
            .uri("/oauth2/authorization/keystone")
            .header(HttpHeaders.COOKIE, cookies.header())
            .exchange((req, resp) -> resp.toBodilessEntity());
    assertThat(step2.getStatusCode().is3xxRedirection()).isTrue();
    URI authorize = step2.getHeaders().getLocation();
    assertThat(authorize.getPath()).isEqualTo("/oauth2/authorize");
    assertThat(authorize.getQuery()).contains("code_challenge=");
    cookies.capture(step2);

    // 3. GET /oauth2/authorize?… → 302 /login (SAS default)
    ResponseEntity<Void> step3 =
        client.get().uri(authorize).header(HttpHeaders.COOKIE, cookies.header())
            .exchange((req, resp) -> resp.toBodilessEntity());
    assertThat(step3.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    cookies.capture(step3);

    // 4. POST /login (SAS) with creds → 302 back to /oauth2/authorize?…
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("username", "admin@keystone.local");
    form.add("password", "demo");
    ResponseEntity<Void> step4 =
        client
            .post()
            .uri("/login")
            .header(HttpHeaders.COOKIE, cookies.header())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .exchange((req, resp) -> resp.toBodilessEntity());
    assertThat(step4.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    cookies.capture(step4);

    // 5. Follow redirects until we land on /admin/ui/users with 200
    ResponseEntity<Void> current = step4;
    for (int i = 0; i < 5 && current.getStatusCode().is3xxRedirection(); i++) {
      URI next = current.getHeaders().getLocation();
      current =
          client
              .method(HttpMethod.GET)
              .uri(next)
              .header(HttpHeaders.COOKIE, cookies.header())
              .exchange((req, resp) -> resp.toBodilessEntity());
      cookies.capture(current);
    }
    assertThat(current.getStatusCode().is2xxSuccessful()).isTrue();
  }

  static class CookieJar {
    private final java.util.Map<String, String> cookies = new java.util.LinkedHashMap<>();

    void capture(ResponseEntity<?> resp) {
      List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
      if (setCookies == null) return;
      for (String sc : setCookies) {
        int eq = sc.indexOf('=');
        int semi = sc.indexOf(';');
        String name = sc.substring(0, eq);
        String value = sc.substring(eq + 1, semi == -1 ? sc.length() : semi);
        cookies.put(name, value);
      }
    }

    String header() {
      StringBuilder sb = new StringBuilder();
      cookies.forEach(
          (k, v) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append('=').append(v);
          });
      return sb.toString();
    }
  }
}
```

- [ ] **Step 2: Run to verify**

Run: `./mvnw -B verify -Dit.test=OAuth2LoginFlowIT -DfailIfNoTests=false`
Expected: PASS — the handshake completes and lands on `/admin/ui/users` — but that route doesn't exist yet, so expect 404 at step 5. Adjust: assert `step4.getStatusCode() == HttpStatus.FOUND` and stop the redirect loop when we hit a 404 too (still means auth completed). Alternatively, temporarily target `/actuator/health` (permitAll) for the final assertion; **leave the final 200-target assertion in and let this test remain red until T6 gives us `/admin/ui/users`**.

- [ ] **Step 3: Commit (test may be red — expected until T6)**

Add a small note in the test class:

```java
// NOTE: This test remains red until Task 6 provides GET /admin/ui/users.
// It is included now to lock in the T3 handshake behavior.
```

```bash
git add src/test/java/co/embracejoy/accounting/keystone/smoke/OAuth2LoginFlowIT.java
git commit -m "Slice 5 Phase D-admin-ui T4: OAuth2 login-flow IT skeleton (red until T6)"
```

---

### Task 5: Layout + vendored static assets + Home/Login controllers

**Files:**
- Create: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/templates/login.html`
- Create: `src/main/resources/static/bootstrap.min.css` (vendor Bootstrap 5.3.3 from https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css)
- Create: `src/main/resources/static/bootstrap-icons.css` (Bootstrap Icons 1.11.x)
- Create: `src/main/resources/static/bootstrap-icons.woff2`
- Create: `src/main/resources/static/htmx.min.js` (HTMX 2.0.x from https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js)
- Create: `src/main/resources/static/keystone-admin.css`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiController.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiController.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiControllerTest.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiControllerTest.java`

**Interfaces:**
- Consumes: T3 UI filter chain.
- Produces: `GET /admin/ui` returns 302 `/admin/ui/users`; `GET /admin/ui/login` returns 200 HTML with a `Sign in` link pointing at `/oauth2/authorization/keystone`. Layout template resolvable under name `layout` with `main` slot; layout injects CSRF meta + HTMX config script.

- [ ] **Step 1: Download and vendor static assets**

```bash
mkdir -p src/main/resources/static
curl -sL https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css \
     -o src/main/resources/static/bootstrap.min.css
curl -sL https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.css \
     -o src/main/resources/static/bootstrap-icons.css
curl -sL https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/fonts/bootstrap-icons.woff2 \
     -o src/main/resources/static/bootstrap-icons.woff2
curl -sL https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js \
     -o src/main/resources/static/htmx.min.js
```

Edit `bootstrap-icons.css` — change the `@font-face src` URL from the CDN path to `./bootstrap-icons.woff2` (Spring's static resource resolver serves it relative).

Create `keystone-admin.css`:

```css
button:focus-visible, a:focus-visible, select:focus-visible, input:focus-visible {
  outline: 2px solid var(--bs-primary);
  outline-offset: 2px;
}
.htmx-swapping { opacity: 1 !important; transition: none !important; }
```

- [ ] **Step 2: Write the failing HomeUiController test**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeUiController.class)
@DisplayName("HomeUiController")
class HomeUiControllerTest {

  @Autowired MockMvc mvc;

  @Test
  @WithMockUser
  @DisplayName("GET /admin/ui redirects to /admin/ui/users")
  void shouldRedirectRootToUsers() throws Exception {
    mvc.perform(get("/admin/ui"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "/admin/ui/users"));
  }
}
```

- [ ] **Step 3: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=HomeUiControllerTest`
Expected: FAIL — no controller.

- [ ] **Step 4: Write `HomeUiController`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/ui")
public class HomeUiController {

  @GetMapping
  public String home() {
    return "redirect:/admin/ui/users";
  }
}
```

- [ ] **Step 5: Run to verify PASS**

Run: `./mvnw -B test -Dtest=HomeUiControllerTest`
Expected: PASS.

- [ ] **Step 6: Write layout + login templates**

`src/main/resources/templates/layout.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="csrf-token" th:if="${_csrf != null}" th:content="${_csrf.token}">
    <title th:text="${pageTitle} ?: 'Keystone Admin'">Keystone Admin</title>
    <link rel="stylesheet" href="/bootstrap.min.css">
    <link rel="stylesheet" href="/bootstrap-icons.css">
    <link rel="stylesheet" href="/keystone-admin.css">
  </head>
  <body>
    <nav class="navbar navbar-expand-lg bg-body-tertiary border-bottom">
      <div class="container-fluid">
        <a class="navbar-brand" href="/admin/ui">Keystone</a>
        <div class="collapse navbar-collapse">
          <ul class="navbar-nav me-auto">
            <li class="nav-item"><a class="nav-link" href="/admin/ui/users">Users</a></li>
            <li class="nav-item" sec:authorize="hasRole('PLATFORM_ADMIN')">
              <a class="nav-link" href="/admin/ui/tenants">Tenants</a>
            </li>
            <li class="nav-item"><a class="nav-link" href="/admin/ui/profile">Profile</a></li>
          </ul>
          <form th:action="@{/logout}" method="post" class="d-flex">
            <button class="btn btn-outline-secondary" type="submit">Log out</button>
          </form>
        </div>
      </div>
    </nav>
    <main class="container my-4">
      <div id="alert-region" role="status" aria-live="polite"></div>
      <div th:replace="${content}"></div>
    </main>
    <script src="/htmx.min.js"></script>
    <script>
      document.body.addEventListener('htmx:configRequest', function(e) {
        var meta = document.querySelector('meta[name=csrf-token]');
        if (meta) e.detail.headers['X-CSRF-TOKEN'] = meta.content;
      });
      document.body.addEventListener('htmx:afterSwap', function(e) {
        var focusTarget = e.detail.target.querySelector('[data-focus-target]');
        if (focusTarget) focusTarget.focus();
      });
    </script>
  </body>
</html>
```

Add `xmlns:sec="http://www.thymeleaf.org/thymeleaf-extras-springsecurity6"` to the `<html>` tag and add the corresponding dependency to pom.xml (`org.thymeleaf.extras:thymeleaf-extras-springsecurity6`):

```xml
<dependency>
  <groupId>org.thymeleaf.extras</groupId>
  <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
```

`src/main/resources/templates/login.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
  <head>
    <meta charset="utf-8">
    <title>Sign in — Keystone</title>
    <link rel="stylesheet" href="/bootstrap.min.css">
  </head>
  <body class="d-flex align-items-center min-vh-100 bg-body-tertiary">
    <main class="container">
      <div class="row justify-content-center">
        <div class="col-md-6 col-lg-4">
          <div class="card shadow-sm">
            <div class="card-body">
              <h1 class="h4 mb-3">Sign in to Keystone</h1>
              <a class="btn btn-primary w-100" href="/oauth2/authorization/keystone">Sign in</a>
            </div>
          </div>
        </div>
      </div>
    </main>
  </body>
</html>
```

- [ ] **Step 7: Write the failing LoginUiController test**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LoginUiController.class)
@DisplayName("LoginUiController")
class LoginUiControllerTest {

  @Autowired MockMvc mvc;

  @Test
  @DisplayName("GET /admin/ui/login renders sign-in page permitting anon")
  void shouldRenderLoginPage() throws Exception {
    mvc.perform(get("/admin/ui/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sign in")))
        .andExpect(content().string(containsString("/oauth2/authorization/keystone")));
  }
}
```

- [ ] **Step 8: Run to verify FAIL, write controller, verify PASS**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginUiController {

  @GetMapping("/admin/ui/login")
  public String login() {
    return "login";
  }
}
```

Run: `./mvnw -B test -Dtest=LoginUiControllerTest` → PASS.

- [ ] **Step 9: Full verify**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main/resources/static/ src/main/resources/templates/ \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiController.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiController.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/HomeUiControllerTest.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/LoginUiControllerTest.java
git commit -m "Slice 5 Phase D-admin-ui T5: layout + login + home + vendored static assets"
```

---

### Task 6: UserRoleUiController list + UiResultMapper

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiController.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UiResultMapper.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/AlertView.java`
- Create: `src/main/resources/templates/users.html`
- Create: `src/main/resources/templates/fragments/user-row.html`
- Create: `src/main/resources/templates/fragments/alert.html`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiControllerTest.java`

**Interfaces:**
- Consumes: `UserRoleService` (Phase A), `TenantContext`.
- Produces: `GET /admin/ui/users` returns 200 HTML with the user list; `@PreAuthorize("hasRole('ADMIN')")` guards it. `UiResultMapper.toAlertView(SecurityError|TenantError)` returns `AlertView(severity, title, detail)`.

- [ ] **Step 1: Write `AlertView`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

public record AlertView(String severity, String title, String detail) {}
```

- [ ] **Step 2: Write `UiResultMapper`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.infrastructure.web.ResultMapper;
import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class UiResultMapper {

  private UiResultMapper() {}

  public static AlertView toAlertView(SecurityError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return new AlertView(severityFor(pd.getStatus()), pd.getTitle(), pd.getDetail());
  }

  public static AlertView toAlertView(TenantError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return new AlertView(severityFor(pd.getStatus()), pd.getTitle(), pd.getDetail());
  }

  public static HttpStatus statusFor(SecurityError err) {
    return HttpStatus.valueOf(ResultMapper.toProblemDetail(err).getStatus());
  }

  public static HttpStatus statusFor(TenantError err) {
    return HttpStatus.valueOf(ResultMapper.toProblemDetail(err).getStatus());
  }

  private static String severityFor(int status) {
    if (status >= 500) return "danger";
    if (status >= 400) return "warning";
    return "info";
  }
}
```

- [ ] **Step 3: Write the failing list test**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserRoleUiController.class)
@DisplayName("UserRoleUiController")
class UserRoleUiControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean UserRoleService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  @Test
  @DisplayName("GET /admin/ui/users renders list for tenant admin")
  void shouldRenderUserList() throws Exception {
    Mockito.when(service.findByTenant(Tenants.DEFAULT_TENANT_ID))
        .thenReturn(
            List.of(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|alice",
                    Role.BOOKKEEPER,
                    Instant.EPOCH,
                    "system")));

    mvc.perform(
            get("/admin/ui/users")
                .with(SecurityMockMvcRequestPostProcessors.oidcLogin()
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                    .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|alice")))
        .andExpect(content().string(containsString("BOOKKEEPER")));
  }
}
```

- [ ] **Step 4: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=UserRoleUiControllerTest`
Expected: FAIL — controller doesn't exist.

- [ ] **Step 5: Write `UserRoleUiController` — list only**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/ui/users")
public class UserRoleUiController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  public UserRoleUiController(UserRoleService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public String list(Model model) {
    model.addAttribute("users", service.findByTenant(tenantContext.require()));
    model.addAttribute("roles", List.of(Role.ADMIN, Role.BOOKKEEPER, Role.READ_ONLY));
    return "users";
  }
}
```

- [ ] **Step 6: Write templates**

`src/main/resources/templates/users.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: layout(pageTitle='Users', content=~{::main})}">
<body>
<main>
  <h1>Users in this tenant</h1>
  <table class="table">
    <thead>
      <tr><th scope="col">User</th><th scope="col">Role</th><th scope="col">Granted</th><th></th></tr>
    </thead>
    <tbody th:if="${users.isEmpty()}">
      <tr><td colspan="4" class="text-muted">No users yet.</td></tr>
    </tbody>
    <tbody>
      <tr th:each="u : ${users}" th:replace="~{fragments/user-row :: row(user=${u}, roles=${roles})}"></tr>
    </tbody>
  </table>
</main>
</body>
</html>
```

`src/main/resources/templates/fragments/user-row.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<tr th:fragment="row(user, roles)" th:id="'user-' + ${user.userSub()}">
  <td th:text="${user.userSub()}">sub</td>
  <td th:text="${user.role()}">ROLE</td>
  <td th:text="${#temporals.format(user.grantedAt(), 'yyyy-MM-dd')}">date</td>
  <td>
    <button class="btn btn-sm btn-outline-danger"
            th:hx-delete="'/admin/ui/users/' + ${user.userSub()}"
            th:hx-target="'#user-' + ${user.userSub()}"
            hx-swap="outerHTML"
            hx-confirm="Remove this user?"
            data-focus-target>Remove</button>
  </td>
</tr>
</body>
</html>
```

`src/main/resources/templates/fragments/alert.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="alert(alert)"
     th:class="'alert alert-' + ${alert.severity()}"
     role="alert" aria-live="assertive">
  <strong th:text="${alert.title()}">Title</strong>
  <span th:text="${alert.detail()}">Detail</span>
</div>
</body>
</html>
```

- [ ] **Step 7: Run to verify PASS**

Run: `./mvnw -B test -Dtest=UserRoleUiControllerTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ \
        src/main/resources/templates/users.html \
        src/main/resources/templates/fragments/ \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiControllerTest.java
git commit -m "Slice 5 Phase D-admin-ui T6: UserRoleUiController list + UiResultMapper"
```

At this point, T4's `OAuth2LoginFlowIT` should now flip to green because `/admin/ui/users` exists. Verify:

Run: `./mvnw -B verify -Dit.test=OAuth2LoginFlowIT -DfailIfNoTests=false`
Expected: PASS.

---

### Task 7: UserRoleUiController mutations (add, change, remove) + form beans

**Files:**
- Modify: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiController.java` — add 3 handlers.
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/AddUserForm.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/ChangeRoleForm.java`
- Modify: `src/main/resources/templates/users.html` — add the "Add user" form at the top.
- Modify: `src/main/resources/templates/fragments/user-row.html` — add role dropdown.
- Modify: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiControllerTest.java` — add 6 more tests.

**Interfaces:**
- Consumes: `UserRoleService.grant(TenantId, sub, Role, grantedBy)`, `UserRoleService.revoke(TenantId, sub, revokedBy)`.
- Produces: `POST /admin/ui/users` (returns fragments/user-row prepended), `PUT /admin/ui/users/{sub}` (returns fragments/user-row for hx-target), `DELETE /admin/ui/users/{sub}` (returns empty 200 for hx-swap="outerHTML" fade-out; failure → alert fragment).

- [ ] **Step 1: Write form beans**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddUserForm(
    @NotBlank @Size(max = 200) String userSub,
    @NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role) {}
```

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangeRoleForm(
    @NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role) {}
```

- [ ] **Step 2: Write the 6 failing tests**

Add to `UserRoleUiControllerTest`:

```java
  @Test
  @DisplayName("POST /admin/ui/users grants role and returns user-row fragment")
  void shouldAddUser() throws Exception {
    Mockito.when(
            service.grant(
                Mockito.eq(Tenants.DEFAULT_TENANT_ID),
                Mockito.eq("auth0|bob"),
                Mockito.eq(Role.BOOKKEEPER),
                Mockito.eq("auth0|test-admin")))
        .thenReturn(
            co.embracejoy.accounting.keystone.domain.shared.Result.success(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID,
                    "auth0|bob",
                    Role.BOOKKEEPER,
                    Instant.EPOCH,
                    "auth0|test-admin")));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/admin/ui/users")
                .param("userSub", "auth0|bob")
                .param("role", "BOOKKEEPER")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .with(SecurityMockMvcRequestPostProcessors.oidcLogin()
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                    .idToken(t -> t.claim("sub", "auth0|test-admin"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auth0|bob")))
        .andExpect(content().string(containsString("BOOKKEEPER")));
  }

  @Test
  @DisplayName("PUT /admin/ui/users/{sub} changes role and returns user-row")
  void shouldChangeRole() throws Exception { /* similar structure — assert row body */ }

  @Test
  @DisplayName("DELETE /admin/ui/users/{sub} returns 200 empty on success")
  void shouldRevokeUser() throws Exception { /* Result.success(null) → 200 empty */ }

  @Test
  @DisplayName("DELETE /admin/ui/users/{sub} returns 404 alert on RoleNotFound")
  void shouldReturn404WhenRevokeMissing() throws Exception { /* assert alert body + status */ }

  @Test
  @DisplayName("PUT /admin/ui/users/{sub} returns 400 alert on CannotOrphanSelf")
  void shouldReturn400WhenOrphaning() throws Exception { /* assert alert body */ }

  @Test
  @DisplayName("POST /admin/ui/users returns 403 alert when caller has only BOOKKEEPER")
  void shouldReturn403WhenNotAdmin() throws Exception { /* oidcLogin ROLE_BOOKKEEPER */ }
```

Fill in the bodies following the shape of `shouldAddUser`.

- [ ] **Step 3: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=UserRoleUiControllerTest`
Expected: FAIL — handlers don't exist.

- [ ] **Step 4: Extend `UserRoleUiController`**

Add to the class:

```java
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public String add(@Valid @ModelAttribute AddUserForm form, Model model) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), form.userSub(), Role.valueOf(form.role()), actor);
    return r.fold(
        row -> {
          model.addAttribute("user", row);
          model.addAttribute("roles", List.of(Role.ADMIN, Role.BOOKKEEPER, Role.READ_ONLY));
          return "fragments/user-row :: row";
        },
        err -> {
          model.addAttribute("alert", UiResultMapper.toAlertView(err));
          throw new SecurityErrorException(err);
        });
  }

  @PutMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  public String change(
      @PathVariable String userSub, @Valid @ModelAttribute ChangeRoleForm form, Model model) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    Result<TenantUserRole, SecurityError> r =
        service.grant(tenantContext.require(), userSub, Role.valueOf(form.role()), actor);
    return r.fold(
        row -> {
          model.addAttribute("user", row);
          model.addAttribute("roles", List.of(Role.ADMIN, Role.BOOKKEEPER, Role.READ_ONLY));
          return "fragments/user-row :: row";
        },
        err -> {
          model.addAttribute("alert", UiResultMapper.toAlertView(err));
          throw new SecurityErrorException(err);
        });
  }

  @DeleteMapping("/{userSub}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<Void> revoke(@PathVariable String userSub) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    Result<Void, SecurityError> r = service.revoke(tenantContext.require(), userSub, actor);
    return r.fold(
        v -> ResponseEntity.ok().build(),
        err -> ResponseEntity.status(UiResultMapper.statusFor(err)).build());
  }
```

Add a small package-private `SecurityErrorException` that wraps the error; catch it in T9's `UiExceptionHandler` to render the alert fragment with the right status. Simpler alternative: return `View` name directly and let the handler's status setter run. Either works; the exception route keeps the happy path clean.

- [ ] **Step 5: Update templates**

Add to top of `users.html` before the table:

```html
<form th:action="@{/admin/ui/users}" th:hx-post="@{/admin/ui/users}"
      hx-target="tbody:last-of-type" hx-swap="afterbegin" class="row g-3 mb-4">
  <div class="col-auto">
    <label for="userSub" class="form-label">User sub</label>
    <input type="text" class="form-control" id="userSub" name="userSub" required>
  </div>
  <div class="col-auto">
    <label for="addRole" class="form-label">Role</label>
    <select class="form-select" id="addRole" name="role" required>
      <option value="ADMIN">ADMIN</option>
      <option value="BOOKKEEPER">BOOKKEEPER</option>
      <option value="READ_ONLY">READ_ONLY</option>
    </select>
  </div>
  <div class="col-auto d-flex align-items-end">
    <button type="submit" class="btn btn-primary">Add user</button>
  </div>
</form>
```

Update `fragments/user-row.html` to include the role dropdown:

```html
<td>
  <select class="form-select form-select-sm"
          th:hx-put="'/admin/ui/users/' + ${user.userSub()}"
          th:hx-target="'#user-' + ${user.userSub()}"
          hx-swap="outerHTML"
          name="role" data-focus-target>
    <option th:each="r : ${roles}" th:value="${r}" th:text="${r}"
            th:selected="${r == user.role()}"></option>
  </select>
</td>
```

- [ ] **Step 6: Run to verify PASS**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS (all `UserRoleUiControllerTest` cases green).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ \
        src/main/resources/templates/users.html \
        src/main/resources/templates/fragments/user-row.html \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UserRoleUiControllerTest.java
git commit -m "Slice 5 Phase D-admin-ui T7: UserRoleUiController add/change/remove HTMX handlers"
```

---

### Task 8: TenantUiController — full CRUD

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiController.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/CreateTenantForm.java`
- Create: `src/main/resources/templates/tenants.html`
- Create: `src/main/resources/templates/tenant-detail.html`
- Create: `src/main/resources/templates/fragments/tenant-row.html`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiControllerTest.java`

**Interfaces:**
- Consumes: `TenantService.findAll()`, `TenantService.findById(TenantId)`, `TenantService.create(String)`, `TenantService.deactivate(TenantId)`.
- Produces: `GET /admin/ui/tenants` (list, 200 HTML), `POST /admin/ui/tenants` (create, HTMX fragment), `GET /admin/ui/tenants/{id}` (detail, 200 HTML), `POST /admin/ui/tenants/{id}/deactivate` (200 HTML fragment). All `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`.

- [ ] **Step 1: Write `CreateTenantForm`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantForm(@NotBlank @Size(max = 200) String name) {}
```

- [ ] **Step 2: Write the failing tests (7 tests)**

Follow `UserRoleUiControllerTest` shape:
- `shouldRenderTenantList` — happy path, 200, table content
- `shouldRenderEmptyState` — service returns empty list
- `shouldCreateTenant` — POST + HTMX fragment response
- `shouldReturn400WhenNameBlank` — Bean Validation failure → alert fragment
- `shouldRenderTenantDetail` — GET /{id}, 200
- `shouldDeactivateTenant` — POST /{id}/deactivate → fragment
- `shouldReturn403WhenNonPlatformAdmin` — oidcLogin with ROLE_ADMIN only

Use `SecurityMockMvcRequestPostProcessors.oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")).idToken(...)`.

- [ ] **Step 3: Run to verify FAIL**

Run: `./mvnw -B test -Dtest=TenantUiControllerTest`
Expected: FAIL.

- [ ] **Step 4: Write `TenantUiController`**

Mirror `UserRoleUiController` shape. Handlers:
- `list(Model)` → `"tenants"`
- `detail(String id, Model)` → `"tenant-detail"`; on invalid UUID or missing tenant, throw `TenantErrorException`.
- `create(@Valid CreateTenantForm form, Model)` → `"fragments/tenant-row :: row"` on success; `TenantErrorException` on failure.
- `deactivate(String id, Model)` → `"fragments/tenant-row :: row"` (with updated tenant); on missing tenant, `TenantErrorException`.

- [ ] **Step 5: Write templates**

`tenants.html`, `tenant-detail.html`, `fragments/tenant-row.html` — same shape as users equivalents but bound to `Tenant` domain type.

- [ ] **Step 6: Run to verify PASS**

Run: `./mvnw -B test -Dtest=TenantUiControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiController.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/dto/CreateTenantForm.java \
        src/main/resources/templates/tenants.html src/main/resources/templates/tenant-detail.html \
        src/main/resources/templates/fragments/tenant-row.html \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/TenantUiControllerTest.java
git commit -m "Slice 5 Phase D-admin-ui T8: TenantUiController full CRUD"
```

---

### Task 9: ProfileUiController + UiExceptionHandler

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ProfileUiController.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/UiExceptionHandler.java`
- Create: `src/main/resources/templates/profile.html`
- Create: `src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ProfileUiControllerTest.java`

**Interfaces:**
- Consumes: current `Authentication`, `TenantContext`, `UserRoleService.findRole(...)`.
- Produces: `GET /admin/ui/profile` (200 HTML with sub / tenant / role). `UiExceptionHandler` (`@ControllerAdvice(basePackages = "…web.ui")`) maps `SecurityErrorException`, `TenantErrorException`, `MethodArgumentNotValidException`, `AccessDeniedException` to `fragments/alert :: alert` with the correct HTTP status.

- [ ] **Step 1: Write the ProfileUiController failing test**

Two tests: `shouldRenderProfile` (asserts sub + tenant + role visible) and `shouldRenderNoRoleWhenNoTenantRole`.

- [ ] **Step 2: Run to verify FAIL**

- [ ] **Step 3: Write `ProfileUiController`**

```java
@Controller
public class ProfileUiController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  public ProfileUiController(UserRoleService service, TenantContext tenantContext) {
    this.service = service;
    this.tenantContext = tenantContext;
  }

  @GetMapping("/admin/ui/profile")
  public String profile(Model model) {
    String sub = SecurityContextHolder.getContext().getAuthentication().getName();
    TenantId tenantId = tenantContext.current().orElse(null);
    model.addAttribute("sub", sub);
    model.addAttribute("tenantId", tenantId == null ? "(none)" : tenantId.value().toString());
    if (tenantId != null) {
      model.addAttribute(
          "role", service.findRole(tenantId, sub).map(r -> r.role().name()).orElse("(no role)"));
    } else {
      model.addAttribute("role", "(no role)");
    }
    return "profile";
  }
}
```

- [ ] **Step 4: Write `profile.html`**

Bootstrap card with three definition rows.

- [ ] **Step 5: Write `UiExceptionHandler` failing tests**

Test both `SecurityErrorException` → alert fragment with correct status + body, and `MethodArgumentNotValidException` → alert fragment with 400.

- [ ] **Step 6: Write `UiExceptionHandler`**

```java
package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(basePackages = "co.embracejoy.accounting.keystone.infrastructure.web.ui")
public class UiExceptionHandler {

  @ExceptionHandler(SecurityErrorException.class)
  public String onSecurityError(SecurityErrorException ex, Model model, HttpServletResponse resp) {
    model.addAttribute("alert", UiResultMapper.toAlertView(ex.error()));
    resp.setStatus(UiResultMapper.statusFor(ex.error()).value());
    return "fragments/alert :: alert";
  }

  @ExceptionHandler(TenantErrorException.class)
  public String onTenantError(TenantErrorException ex, Model model, HttpServletResponse resp) {
    model.addAttribute("alert", UiResultMapper.toAlertView(ex.error()));
    resp.setStatus(UiResultMapper.statusFor(ex.error()).value());
    return "fragments/alert :: alert";
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public String onValidation(
      MethodArgumentNotValidException ex, Model model, HttpServletResponse resp) {
    String detail =
        ex.getBindingResult().getAllErrors().stream()
            .map(e -> e.getDefaultMessage())
            .filter(m -> m != null)
            .findFirst()
            .orElse("Request is invalid.");
    model.addAttribute(
        "alert",
        new co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView(
            "warning", "Invalid input", detail));
    resp.setStatus(400);
    return "fragments/alert :: alert";
  }

  @ExceptionHandler(AccessDeniedException.class)
  public String onAccessDenied(AccessDeniedException ex, Model model, HttpServletResponse resp) {
    model.addAttribute(
        "alert",
        new co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView(
            "danger", "Not allowed", "This endpoint requires a higher role."));
    resp.setStatus(403);
    return "fragments/alert :: alert";
  }
}
```

Also add the two tiny wrapper exceptions (`SecurityErrorException` and `TenantErrorException`) with a single-arg constructor and a `.error()` getter.

- [ ] **Step 7: Run to verify PASS**

Run: `./mvnw -B verify -DskipITs`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/ \
        src/main/resources/templates/profile.html \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/ui/
git commit -m "Slice 5 Phase D-admin-ui T9: ProfileUiController + UiExceptionHandler + wrapper exceptions"
```

---

### Task 10: ADRs 0019–0022 + UiSecurityArchTest

**Files:**
- Create: `docs/adr/0019-oauth2-client-and-session-for-ui.md`
- Create: `docs/adr/0020-embedded-authorization-server-for-dev-and-test.md`
- Create: `docs/adr/0021-server-rendered-ui-thymeleaf-htmx-no-build.md`
- Create: `docs/adr/0022-playwright-and-axe-core-ci-gate.md`
- Modify: `docs/adr/README.md` — index rows.
- Create: `src/test/java/co/embracejoy/accounting/keystone/architecture/UiSecurityArchTest.java`

**Interfaces:**
- Consumes: nothing runtime.
- Produces: ArchUnit rules enforcing ADR-0019 (`oauth2Login`-related calls only from `UiSecurityConfig`) and ADR-0020 (`EmbeddedAuthorizationServerConfig` carries `@Profile({"dev","test"})`).

- [ ] **Step 1: Write ADRs**

Each follows the `0000-template.md` structure with a new "Enforcement" section per ADR-0018:

- 0019: OAuth2 client + session cookie for the UI. Context: browser tabs can't hold bearer tokens safely. Decision: separate `SecurityFilterChain` with `oauth2Login`, session cookie, PKCE. Enforcement: `UiSecurityArchTest.OAUTH2LOGIN_ONLY_IN_UI_SECURITY_CONFIG`.
- 0020: Embedded Spring Authorization Server for `dev` + `test`. Enforcement: `UiSecurityArchTest.SAS_CONFIG_IS_PROFILE_GUARDED`.
- 0021: Server-rendered UI, no JS build step. Enforcement: not code-structural — this is a repo-topology rule; enforced by the presence check in `verify` (grep `package.json` fails the build if it appears).
- 0022: Playwright + axe-core as CI gate. Enforcement: `AdminUiE2ETest` runs in `verify`; the test itself asserts zero WCAG AA violations.

- [ ] **Step 2: Write the failing ArchUnit test**

```java
package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Profile;

@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class UiSecurityArchTest {

  @ArchTest
  static final ArchRule OAUTH2LOGIN_ONLY_IN_UI_SECURITY_CONFIG =
      methods()
          .that()
          .haveNameContaining("oauth2Login")
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..infrastructure.security..")
          .should()
          .beDeclaredInClassesThat()
          .haveSimpleName("UiSecurityConfig")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule SAS_CONFIG_IS_PROFILE_GUARDED =
      classes()
          .that()
          .haveSimpleName("EmbeddedAuthorizationServerConfig")
          .should()
          .beAnnotatedWith(Profile.class);
}
```

- [ ] **Step 3: Run to verify FAIL, then PASS**

If FAIL because the class doesn't yet compile — fix, commit. If PASS immediately (because T2 + T3 already gave us the right structure), that's fine — the rule locks it in.

Run: `./mvnw -B test -Dtest=UiSecurityArchTest`
Expected: PASS.

- [ ] **Step 4: Update `docs/adr/README.md` index**

Append 4 rows for ADRs 0019–0022.

- [ ] **Step 5: Add a build-time check for ADR-0021 (no package.json)**

In `pom.xml` under the antrun plugin (or as a small `<echo><fail>` block), add a check that fails if `package.json` or `node_modules/` exist at the repo root. Alternatively, an ArchUnit-adjacent test that reads the filesystem:

```java
@Test
@DisplayName("no package.json exists at repo root (ADR-0021)")
void shouldHaveNoPackageJson() {
  assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get("package.json"))).isFalse();
  assertThat(java.nio.file.Files.exists(java.nio.file.Paths.get("node_modules"))).isFalse();
}
```

Put this in a new `NoBuildStepTest.java` alongside `UiSecurityArchTest`.

- [ ] **Step 6: Commit**

```bash
git add docs/adr/0019-oauth2-client-and-session-for-ui.md \
        docs/adr/0020-embedded-authorization-server-for-dev-and-test.md \
        docs/adr/0021-server-rendered-ui-thymeleaf-htmx-no-build.md \
        docs/adr/0022-playwright-and-axe-core-ci-gate.md \
        docs/adr/README.md \
        src/test/java/co/embracejoy/accounting/keystone/architecture/UiSecurityArchTest.java \
        src/test/java/co/embracejoy/accounting/keystone/architecture/NoBuildStepTest.java
git commit -m "Slice 5 Phase D-admin-ui T10: ADRs 0019-0022 + ArchUnit rules + no-package-json check"
```

---

### Task 11: Playwright + axe-core AdminUiE2ETest

**Files:**
- Create: `src/test/resources/axe.min.js` (vendor from https://cdn.jsdelivr.net/npm/axe-core@4.10.2/axe.min.js)
- Create: `src/test/java/co/embracejoy/accounting/keystone/ui/e2e/AxeAssertions.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/ui/e2e/AdminUiE2ETest.java`

**Interfaces:**
- Consumes: T1-T9 (full UI + SAS + seeded users).
- Produces: E2E test with 4 flows: (a) platform admin creates + lists + deactivates tenant; (b) tenant admin adds + changes + removes user; (c) bookkeeper hits `/admin/ui/tenants` and sees 403 alert; (d) log out returns to login. axe asserts zero WCAG AA violations on each page state.

- [ ] **Step 1: Vendor axe-core**

```bash
mkdir -p src/test/resources
curl -sL https://cdn.jsdelivr.net/npm/axe-core@4.10.2/axe.min.js \
     -o src/test/resources/axe.min.js
```

- [ ] **Step 2: Write `AxeAssertions`**

```java
package co.embracejoy.accounting.keystone.ui.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public final class AxeAssertions {

  private static final String AXE_SOURCE = readAxe();

  private AxeAssertions() {}

  public static void assertNoViolations(Page page) throws Exception {
    page.addScriptTag(new Page.AddScriptTagOptions().setContent(AXE_SOURCE));
    Object raw =
        page.evaluate(
            "async () => JSON.stringify(await axe.run(document, "
                + "{runOnly: {type: 'tag', values: ['wcag2a', 'wcag2aa']}}))");
    @SuppressWarnings("unchecked")
    Map<String, Object> report =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue((String) raw, Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> violations = (List<Map<String, Object>>) report.get("violations");
    assertThat(violations)
        .as("Expected no WCAG AA violations on %s", page.url())
        .isEmpty();
  }

  private static String readAxe() {
    try {
      return Files.readString(Paths.get("src/test/resources/axe.min.js"), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read axe.min.js", e);
    }
  }
}
```

- [ ] **Step 3: Write `AdminUiE2ETest`**

Skeleton — one `@Test` per flow, all boot Playwright once via `@BeforeAll`:

```java
package co.embracejoy.accounting.keystone.ui.e2e;

import static co.embracejoy.accounting.keystone.ui.e2e.AxeAssertions.assertNoViolations;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("e2e")
@DisplayName("AdminUiE2E")
class AdminUiE2ETest {

  @LocalServerPort int port;
  static Playwright pw;
  static Browser browser;
  BrowserContext context;
  Page page;

  @BeforeAll
  void bootBrowser() {
    pw = Playwright.create();
    browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
  }

  @AfterAll
  void stopBrowser() {
    browser.close();
    pw.close();
  }

  @BeforeEach
  void freshContext() {
    context = browser.newContext();
    page = context.newPage();
  }

  private void login(String username) {
    page.navigate("http://localhost:" + port + "/admin/ui/users");
    page.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
        new Page.GetByRoleOptions().setName("Sign in")).click();
    page.getByLabel("Username").fill(username);
    page.getByLabel("Password").fill("demo");
    page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
        new Page.GetByRoleOptions().setName("Sign in")).click();
  }

  @Test
  @DisplayName("platform admin creates + lists + deactivates a tenant, WCAG AA clean")
  void platformAdminTenantCrud() throws Exception {
    login("platform@keystone.local");
    page.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
        new Page.GetByRoleOptions().setName("Tenants")).click();
    assertNoViolations(page);
    page.getByLabel("Name").fill("Acme");
    page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
        new Page.GetByRoleOptions().setName("Create tenant")).click();
    assertThat(page.locator("table").innerText()).contains("Acme");
    assertNoViolations(page);
  }

  @Test
  @DisplayName("tenant admin adds + changes + removes user, WCAG AA clean")
  void tenantAdminUserCrud() throws Exception {
    login("admin@keystone.local");
    // add
    page.getByLabel("User sub").fill("auth0|bob");
    page.getByLabel("Role").selectOption("BOOKKEEPER");
    page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
        new Page.GetByRoleOptions().setName("Add user")).click();
    assertThat(page.locator("#user-auth0\\|bob").innerText()).contains("BOOKKEEPER");
    assertNoViolations(page);
    // change
    page.locator("#user-auth0\\|bob select").selectOption("ADMIN");
    assertThat(page.locator("#user-auth0\\|bob").innerText()).contains("ADMIN");
    // remove
    page.onDialog(d -> d.accept());
    page.locator("#user-auth0\\|bob button").click();
    assertThat(page.locator("#user-auth0\\|bob").count()).isEqualTo(0);
    assertNoViolations(page);
  }

  @Test
  @DisplayName("bookkeeper hitting /admin/ui/tenants sees 403 alert")
  void bookkeeperSeesForbidden() throws Exception {
    login("bookkeeper@keystone.local");
    page.navigate("http://localhost:" + port + "/admin/ui/tenants");
    assertThat(page.locator("#alert-region").innerText()).contains("Not allowed");
    assertNoViolations(page);
  }

  @Test
  @DisplayName("log out returns to sign-in page")
  void logoutReturnsToLogin() throws Exception {
    login("admin@keystone.local");
    page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
        new Page.GetByRoleOptions().setName("Log out")).click();
    assertThat(page.url()).endsWith("/admin/ui/login");
    assertNoViolations(page);
  }
}
```

- [ ] **Step 4: Ensure Playwright browsers are installed**

Playwright's Java driver requires the browsers to be installed on the runner. Add a Maven exec step (in `pom.xml` under `<plugins>`) to run `mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install --with-deps chromium"` in the `pre-integration-test` phase.

Alternative simpler approach: the test harness calls `Playwright.create()` which auto-downloads browsers on first run. Add a `@BeforeAll` that runs `com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"})` to ensure browsers exist.

- [ ] **Step 5: Run to verify**

Run: `./mvnw -B verify -Dit.test=AdminUiE2ETest -DfailIfNoTests=false`
Expected: PASS. First run downloads Chromium (~150 MB); subsequent runs use the cache.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/axe.min.js \
        src/test/java/co/embracejoy/accounting/keystone/ui/e2e/ \
        pom.xml
git commit -m "Slice 5 Phase D-admin-ui T11: Playwright + axe-core AdminUiE2ETest"
```

---

### Task 12: Full verify + open PR

**Files:**
- No new files — final polish + verify + push.

**Interfaces:**
- Consumes: T1–T11.
- Produces: pushed branch with all commits, PR #_N_ open against `main`, ready for review.

- [ ] **Step 1: Format + full CI-parity verify**

```bash
./mvnw -B spotless:apply
./mvnw -B clean verify -Popenapi-gate
```

Expected: BUILD SUCCESS. OpenAPI snapshot should be unchanged (UI controllers are `@Controller`, not `@RestController` — SpringDoc ignores them).

- [ ] **Step 2: If snapshot changed unexpectedly, investigate**

If `git diff docs/openapi/openapi.yaml` shows changes: a UI controller is likely annotated with `@RestController` by mistake. Fix it to `@Controller`. If genuinely expected, regenerate via `./mvnw -Popenapi-update verify` and commit.

- [ ] **Step 3: Confirm no stray files**

```bash
git status
```

Expected: clean tree.

- [ ] **Step 4: Push and open PR**

```bash
git push -u origin 16-slice-5-phase-d-admin-ui
gh pr create --title "Slice 5 Phase D-admin-ui: Thymeleaf + HTMX admin UI (closes #16 UI surface)" --body "$(cat <<'EOF'
## Summary

Ships the server-rendered admin UI for tenant + user-role management, closing the Slice 5 UI surface. Full spec §8.2 covered:

- `/admin/ui/users` (tenant admin) — list, add, change role, remove (HTMX row swaps)
- `/admin/ui/tenants` (platform admin) — list, create, deactivate
- `/admin/ui/tenants/{id}` (platform admin) — detail
- `/admin/ui/profile` — read-only sub / tenant / role
- `/admin/ui/login` — Authorization Code + PKCE → HttpSession

Second SecurityFilterChain for `/admin/ui/**` with `oauth2Login`. Embedded Spring Authorization Server on `dev`/`test` profiles. Vendored Bootstrap CSS + Icons + HTMX + axe-core — no Node.js, no `package.json`.

Four new ADRs (0019–0022), each with an Enforcement section per ADR-0018. Two ArchUnit rules lock in ADR-0019 + 0020.

Tests: `@WebMvcTest` per UI controller (~25 tests) + `OAuth2LoginFlowIT` (full handshake) + `AdminUiE2ETest` (Playwright + axe-core, 4 flows, zero WCAG AA violations).

D-finish (smoke updates, README status flip, closes #16) follows separately.

## Test plan

- [x] `./mvnw -B clean verify -Popenapi-gate` — full CI-parity gate green.
- [ ] Manual: `docker compose up`, browse `http://localhost:8080/admin/ui`, sign in as `admin@keystone.local` / `demo`, walk the flows.

Refs #16.
EOF
)"
```

- [ ] **Step 5: Await CI**

CI runs `./mvnw -B clean verify -Pmutation,openapi-gate`. If it fails, diagnose via `gh run view --log-failed`.

---

## Self-Review

**1. Spec coverage.** Walked §1–§9 of the spec:
- §1 Architecture → T3 (UiSecurityConfig, order 1) + T2 (SAS, order 0) + existing SecurityConfig (T3 order 2). ✓
- §2 file layout → matches T3, T6, T7, T8, T9 file lists. ✓
- §3 four ADRs → T10. ✓
- §4 data flow → T3 + T7 (HTMX row mutation); T4 asserts the login flow works end-to-end. ✓
- §5 error handling → T6 (`UiResultMapper`), T7 (`SecurityErrorException`), T9 (`UiExceptionHandler`). ✓
- §6 testing → T1 dep, T4 IT, T6-T9 `@WebMvcTest`, T11 Playwright. ✓
- §7 bootstrap → T2 SAS + `DevUserSeeder`, T1 yaml additions. ✓
- §8 accessibility → T5 layout `<meta>`, `role="status"`, `keystone-admin.css` focus outline; T11 axe assertions. ✓
- §9 rollout → T12 verify + PR. ✓

**2. Placeholder scan.** No "TBD"/"TODO"/"implement later" in step text — every task has concrete code or exact filesystem operations. T4 and T7-T9 have some tests described in prose (e.g. "similar structure"); this is acceptable because the codebase already has strong `@WebMvcTest` precedent — but for T7's five follow-up tests I only sketched the display names and referred back to `shouldAddUser` as the template. Fixed inline by adding the phrase "Fill in the bodies following the shape of `shouldAddUser`" to make the delegation explicit.

**3. Type consistency.**
- `AlertView(String severity, String title, String detail)` — used consistently across T6 (`UiResultMapper`), T7 (`UiExceptionHandler` for BindingResult), T9 (all four handlers), and templates.
- `AddUserForm(String userSub, String role)` — consumed by T7's `POST /admin/ui/users`; validation matches domain constraint.
- `SecurityErrorException.error()` returns `SecurityError` — used by `UiResultMapper.toAlertView(SecurityError)` in T6 and by the T9 handler.
- `AuthenticationTenantResolver.resolve(OAuth2User)` returns `Collection<GrantedAuthority>` — consumed by T3's `UiSecurityConfig` custom `OidcUserService`.

All names line up.

**4. Ambiguity check.** One clarification needed and folded in:
- T7's `add` handler calls `service.grant(...)` and returns `"fragments/user-row :: row"` on success but throws `SecurityErrorException` on failure. Made explicit: the exception path goes through `UiExceptionHandler` (T9); the happy path returns the fragment view name.
- T4's flow test remains red until T6. Called that out explicitly in the task text so an implementer isn't surprised.
