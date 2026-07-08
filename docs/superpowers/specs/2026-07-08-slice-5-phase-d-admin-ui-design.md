# Slice 5 Phase D-admin-ui — design

**Date:** 2026-07-08
**Refs:** #16, [Slice 5 design spec](2026-05-13-slice-5-tenancy-and-rbac-design.md), [Slice 5 plan](../plans/2026-05-13-slice-5-tenancy.md), ADR-0018

**Scope:** Server-rendered admin UI for tenant + user-role management. Ships the full spec §8.2 surface (users, tenants, profile, login) in one PR. Landing this closes the Slice 5 UI surface; D-finish (smoke + README flip) follows separately.

## 1. Architecture

Two `SecurityFilterChain` beans coexist:

- **API chain** (existing, order 1): matches `/**` except `/admin/ui/**`. Bearer-JWT resource server. Unchanged.
- **UI chain** (new, order 0): matches `/admin/ui/**`. OAuth2 Login (Authorization Code + PKCE) → `HttpSession` cookie. Same `JwtTenantConverter` populates `TenantContext` from the ID-token tenant claim after login.

Both chains resolve `JwtAuthenticationToken` (API) and `OAuth2AuthenticationToken` (UI) into the same request-scoped `TenantContext`, via a small `AuthenticationTenantResolver`. Application services (`TenantService`, `UserRoleService`) never know which surface called them.

An **embedded Spring Authorization Server** runs when profile `dev` or `test` is active. Production sets `keystone.security.issuer-uri` to a real IdP; SAS is not registered there.

UI controllers live at `infrastructure.web.ui.*` — thin adapters returning Thymeleaf view names or fragment names. `UiResultMapper` translates `Result<T, E>` into either a `Model` attribute (success) or an alert fragment (4xx), delegating title/detail text to the existing `ResultMapper.toProblemDetail(...)` so wording stays in one place.

Static assets (`bootstrap.min.css`, `bootstrap-icons.css`, `bootstrap-icons.woff2`, `htmx.min.js`, `keystone-admin.css`) live at `src/main/resources/static/`. Committed to the repo. No Node.js, no `package.json`, no build step.

## 2. Components + file layout

```
src/main/java/co/embracejoy/accounting/keystone/
  infrastructure/
    security/
      UiSecurityConfig.java                     # new filter chain, oauth2Login, session
      AuthenticationTenantResolver.java         # tenant claim → TenantContext for both flows
      EmbeddedAuthorizationServerConfig.java    # @Profile("dev|test")
      DevUserSeeder.java                        # @Profile("dev|test") ApplicationRunner
      HtmxAuthenticationEntryPoint.java         # 302 for HTML, HX-Redirect for HTMX
    web/ui/
      HomeUiController.java                     # GET /admin/ui → 302 /admin/ui/users
      LoginUiController.java                    # GET /admin/ui/login
      UserRoleUiController.java                 # /admin/ui/users list, add, change, remove
      TenantUiController.java                   # /admin/ui/tenants list, create, deactivate
      ProfileUiController.java                  # GET /admin/ui/profile
      UiResultMapper.java                       # Result → Model | alert fragment
      UiExceptionHandler.java                   # @ControllerAdvice for the ui package
      dto/
        AddUserForm.java
        ChangeRoleForm.java
        CreateTenantForm.java
        AlertView.java                          # severity/title/detail record

src/main/resources/
  templates/
    layout.html                                 # <head>, nav, CSRF meta, alert region
    login.html
    users.html
    tenants.html
    tenant-detail.html
    profile.html
    fragments/
      user-row.html
      tenant-row.html
      alert.html
  static/
    bootstrap.min.css
    bootstrap-icons.css
    bootstrap-icons.woff2
    htmx.min.js
    keystone-admin.css

src/test/java/co/embracejoy/accounting/keystone/
  infrastructure/web/ui/
    HomeUiControllerTest.java
    LoginUiControllerTest.java
    UserRoleUiControllerTest.java
    TenantUiControllerTest.java
    ProfileUiControllerTest.java
  smoke/
    OAuth2LoginFlowIT.java                      # @SpringBootTest, TestRestTemplate handshake
  ui/e2e/
    AdminUiE2ETest.java                         # @SpringBootTest, Playwright + axe
    AxeAssertions.java                          # helper
  architecture/
    UiSecurityArchTest.java                     # rules for ADR-0019, ADR-0020

src/test/resources/
  axe.min.js                                    # vendored, used by AdminUiE2ETest
```

Each unit is size-bounded: controllers ≤ ~100 lines, templates focused per page, one `@WebMvcTest` file per controller.

## 3. New ADRs

Four ADRs land alongside this slice, each with an "Enforcement" section per [ADR-0018](../../adr/0018-archunit-enforce-adrs-where-possible.md):

- **ADR-0019** — OAuth2 client + session cookie for the admin UI browser flow. Complements [ADR-0017](../../adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md) (bearer JWT for API). Codifies: same IdP serves both surfaces; `SecurityFilterChain` split by `securityMatcher`; ID-token tenant claim resolves to the same `TenantContext`. Enforcement: ArchUnit rule that `oauth2Login`-related configuration only appears in `UiSecurityConfig`.
- **ADR-0020** — Embedded Spring Authorization Server for `dev` + `test` profiles. Rejects Keycloak testcontainer (slow), cloud IdP (net dependency), and MockUser-only (never exercises the handshake). Enforcement: ArchUnit rule that `EmbeddedAuthorizationServerConfig` carries `@Profile({"dev","test"})` and is not referenced from any always-on config.
- **ADR-0021** — Server-rendered admin UI: Thymeleaf + HTMX 2.x, no JS build step. No `package.json`, no npm/bun, no Node.js. HTMX scope limited to in-place row mutations per spec §8.2. Enforcement: repo-root check (build script or CI grep) that no `package.json` or `node_modules` files exist.
- **ADR-0022** — Playwright + axe-core browser tests as a CI gate. Zero WCAG AA violations required on every page state. Playwright chosen over Selenium for auto-waits and speed. Enforcement: CI runs `AdminUiE2ETest`; the test itself asserts zero violations.

## 4. Data flow

### 4.1 Login (once per session)

```
Browser                    Keystone                   IdP (SAS in dev/test, real in prod)
   │                          │                                    │
   ├─ GET /admin/ui/users ───►│                                    │
   │                          ├─ no session? 302 → /oauth2/authorization/keystone
   │◄─ 302 ──────────────────┤                                    │
   ├─ GET /oauth2/authorization/keystone ─────────────────────────►│
   │                          │◄─ 302 → IdP /authorize?code_challenge=… (PKCE)
   │◄─ 302 ──────────────────┤                                    │
   ├─ IdP login form ────────────────────────────────────────────►│
   │◄─ 302 → /login/oauth2/code/keystone?code=… ─────────────────┤
   ├─ GET /login/oauth2/code/keystone?code=… ────────────────────►│
   │                          ├─ POST /token (code + verifier) ──►│
   │                          │◄─ id_token + access_token ────────┤
   │                          ├─ JwtTenantConverter reads sub + tenant claim
   │                          ├─ populates TenantContext + ROLE_ authorities
   │                          ├─ HttpSession created, cookie set
   │◄─ 302 → /admin/ui/users ─┤                                    │
   ├─ GET /admin/ui/users (with session cookie) ─────────────────►│
   │◄─ 200 HTML ─────────────┤                                    │
```

### 4.2 In-page mutation (HTMX row swap)

```
Browser                    Keystone
   │  [click role dropdown → PUT]
   ├─ HTMX PUT /admin/ui/users/{sub} + CSRF header + hx-request ►│
   │  body: role=BOOKKEEPER
   │                          ├─ Spring resolves session → Authentication
   │                          ├─ JwtTenantConverter (cached) populates TenantContext
   │                          ├─ UserRoleUiController.grant(...)
   │                          ├─ UserRoleService.grant(...) → Result<TenantUserRole, SecurityError>
   │                          ├─ Success: return "fragments/user-row" fragment
   │                          │  Failure: return "fragments/alert" fragment with 4xx status +
   │                          │           HX-Retarget: #alert-region
   │◄─ 200 HTML fragment ────┤
   │  [HTMX swaps the <tr> or shows alert]
```

### 4.3 Key behaviors

- **Same `TenantContext` bean** (request-scoped) serves both filter chains.
- **RBAC** uses `@PreAuthorize` — the same pattern as the API. `UserRoleUiController.grant` carries `@PreAuthorize("hasRole('ADMIN')")`; `TenantUiController.create` carries `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`.
- **CSRF**: Spring's default `CookieCsrfTokenRepository.withHttpOnlyFalse()`. Layout injects `<meta name="csrf-token" content="[[${_csrf.token}]]">`. HTMX picks it up via a small `htmx:configRequest` handler in the layout.
- **Session timeout on HTMX request**: A `HtmxAuthenticationEntryPoint` returns `HX-Redirect: /admin/ui/login` header (200 empty body) instead of a raw 302, so HTMX does a full-page nav instead of trying to swap a login page into a table row.

## 5. Error handling

Domain errors flow through the same `Result<T, E>` types as the API. `UiResultMapper.toAlertFragment(err)` returns an `AlertView(String severity, String title, String detail)` record that the shared `fragments/alert.html` renders. Controller returns view name `fragments/alert :: alert` with the right HTTP status (400/403/404). HTMX auto-swaps into a dedicated `#alert-region`.

Same error text as the API where possible. `UiResultMapper` delegates to `ResultMapper.toProblemDetail(...)` and lifts `title` + `detail` out of the ProblemDetail. One source of wording.

Auth failures at the filter layer:
- No session, HTML request: Spring's `oauth2Login` default — 302 to IdP.
- No session, HTMX request: `HX-Redirect: /admin/ui/login` header + 200 empty body (per §4).
- Session valid, insufficient role: `@PreAuthorize` throws `AccessDeniedException` → alert-fragment path (403).
- Session valid, tenant claim absent/unknown: same `InvalidBearerTokenException` path the API uses, mapped to `fragments/alert` (403) with "no tenant" copy.

Bean Validation on form-backing beans (`AddUserForm`, `CreateTenantForm`, `ChangeRoleForm`): `MethodArgumentNotValidException` handled by `UiExceptionHandler` (`@ControllerAdvice(basePackages = "…web.ui")`) → same alert fragment, 400 status.

Empty states: when a list is empty, the page renders a friendly empty-state row inside the table (`<tbody th:if="${users.isEmpty()}">…"No users yet"…</tbody>`) rather than an alert.

## 6. Testing strategy

Three layers:

**6.1 `@WebMvcTest` per UI controller.** Auto-configures MockMvc with just the target controller + `UiSecurityConfig` + `TestSecurityConfig`. Uses Spring Security's `.with(oidcLogin().authorities(...).claims(c → c.put("sub", "auth0|test-admin")))` to inject a pre-authenticated `OAuth2AuthenticationToken`. Asserts: rendered template name, model attributes, key DOM markers (`xpath()` / `containsString(...)`), HTMX response headers, 403 for wrong role, 401/redirect for no auth. ~4–6 tests per controller, ~25 tests total.

**6.2 One `@SpringBootTest` embedded-SAS integration test** (`OAuth2LoginFlowIT`). Boots the app on a random port with `test` profile → embedded SAS + Postgres testcontainer. `TestRestTemplate` with a cookie jar walks the full handshake without a browser. Proves OAuth2 wiring end-to-end. ~50 lines.

**6.3 Playwright + axe-core E2E** (`AdminUiE2ETest`). Same `@SpringBootTest` boot. Seeds three users in the DB via JdbcClient in `@BeforeEach` matching the SAS-seeded creds. Four flows tested:
- Platform admin creates a tenant, sees it in the list, deactivates it.
- Tenant admin adds a user, changes role via row dropdown (HTMX swap), removes the user (row fades out).
- Bookkeeper hits `/admin/ui/tenants`, sees 403 alert.
- Log out → GET `/admin/ui/users` → redirected to `/admin/ui/login`.

axe-core runs on each page state before the next action; any WCAG AA violation fails the test. `@Tag("e2e")` for potential fast-loop skip; CI runs it.

Test dependency additions: `com.microsoft.playwright:playwright` (test scope), `axe.min.js` vendored to `src/test/resources/`.

Coverage: existing JaCoCo 85% line gate should hold — UI controllers are thin, templates aren't counted. Sanity-check the delta in the PR.

## 7. Bootstrap + local dev

### 7.1 Embedded Spring Authorization Server

`EmbeddedAuthorizationServerConfig` (`@Configuration @Profile({"dev","test"})`) registers SAS beans: `AuthorizationServerSettings`, `RegisteredClientRepository` (one client: `keystone-admin-ui`, PKCE required, redirect URI `{app-base}/login/oauth2/code/keystone`), `UserDetailsService` with three seeded users, JWK source (RSA keypair generated at boot — non-persistent, fine for dev/test).

SAS endpoints ride the same servlet on `/oauth2/authorize`, `/oauth2/token`, `/.well-known/oauth-authorization-server`. The app talks to itself: dev-profile `keystone.security.issuer-uri=http://localhost:8080`.

An `OAuth2TokenCustomizer` copies the seeded user's tenant claim (`https://keystone.embracejoy.co/tenant_id`) into the ID token so the same `JwtTenantConverter` works unchanged.

### 7.2 Seeded users (`dev` + `test`)

| username | password | sub | Roles seeded in DB |
|---|---|---|---|
| `platform@keystone.local` | `demo` | `sas\|platform` | `platform_admins` row + `tenant_user_roles` (default tenant, ADMIN) |
| `admin@keystone.local` | `demo` | `sas\|admin` | `tenant_user_roles` (default tenant, ADMIN) |
| `bookkeeper@keystone.local` | `demo` | `sas\|bookkeeper` | `tenant_user_roles` (default tenant, BOOKKEEPER) |

Seeding:
- **DB rows**: `DevUserSeeder` (`@Component @Profile({"dev","test"}) ApplicationRunner`), further guarded by `@ConditionalOnProperty(name="keystone.dev.seed-users", havingValue="true", matchIfMissing=true)`. Runs after Flyway (Spring Boot's runner ordering guarantees this). Idempotent: uses `INSERT ... ON CONFLICT DO NOTHING` on `platform_admins` and `tenant_user_roles`. Not Flyway — a migration can't read Spring config to skip itself, and we want disable-by-property behavior on dev too.
- **SAS `UserDetailsService`**: hard-coded in `EmbeddedAuthorizationServerConfig`; only present when the SAS bean is (dev/test profiles).

### 7.3 First-tenant / first-admin on a fresh prod deploy

Unchanged from Slice 5 spec §9.2 — documented here for completeness:
- V6 migration seeds one row in `tenants` (the default tenant UUID).
- Operator sets `KEYSTONE_PLATFORM_ADMIN_SUB=<real-idp-sub>` on first boot. `PlatformAdminBootstrap` (Phase C, merged) inserts the `platform_admins` row idempotently. That user logs in via the UI and grants tenant-admin roles to others.
- Prod does not enable SAS. Prod does not run `DevUserSeeder` (`@Profile` excludes it).

### 7.4 docker-compose local flow

`SPRING_PROFILES_ACTIVE=dev` in the app service triggers SAS + `DevUserSeeder` automatically. `keystone.security.issuer-uri` points at `http://app:8080` (compose service DNS) so JWK/discovery works inside the network. Browser goes to `http://localhost:8080/admin/ui`, gets 302 to `http://localhost:8080/oauth2/authorize`, enters `admin@keystone.local` / `demo`, lands back logged in. No external network dependency.

### 7.5 Config additions to `application.yaml`

```yaml
spring:
  profiles:
    active: dev  # overridden per env
keystone:
  security:
    issuer-uri: ${KEYSTONE_ISSUER_URI:http://localhost:8080}
    # ...existing keys...
  dev:
    seed-users: ${KEYSTONE_DEV_SEED_USERS:true}
```

## 8. Accessibility

**Semantic HTML.** One `<h1>` per page. `<h2>` for section headers, never skipped levels. Tables use `<table>`/`<thead>`/`<tbody>`, `<th scope="col">` on column headers — not `<div>` grids. Forms: every `<input>` has an associated `<label>`, no placeholder-as-label. Buttons for mutations, `<a href>` for navigation; never `<div onclick>`.

**Keyboard.** Every interactive element is tab-reachable in visible order. Row actions (change role, remove) are `<button>` elements inside the row, HTMX default `hx-trigger="click"`. Browser default `:focus-visible` outline preserved; `keystone-admin.css` bumps focus outline to `2px solid` on buttons in tables. Confirm-delete uses `hx-confirm="…"` (native `confirm()` — keyboard-accessible).

**Focus management after HTMX swap.** After `hx-swap`, focus can vanish. A ~10-line `htmx:afterSwap` listener in the layout finds `[data-focus-target]` inside the swapped fragment and calls `.focus()`. Response fragments annotate the right element to restore focus to.

**ARIA live regions.** `<div id="alert-region" role="status" aria-live="polite">` in the layout, empty by default. Alert fragments target `#alert-region` with `hx-swap="innerHTML"`. Error alerts (`role="alert" aria-live="assertive"`) interrupt speech for 403/400.

**Contrast.** Bootstrap 5 default palette meets WCAG AA. `keystone-admin.css` does not override text/background colors. If any Bootstrap Icons gray falls below 4.5:1 on our surfaces, we bump to the "dark" utility class — axe-core will flag if it isn't.

**Motion.** Bootstrap fade transitions off (`.htmx-swapping { opacity: 1; }`). No spinning UI on swap.

**Automated verification.** Every `@WebMvcTest` asserts basic structure (single `<h1>`, form labels, buttons vs. links). Playwright + axe-core asserts zero WCAG AA violations on every page state — the real gate.

## 9. Rollout + non-goals

### 9.1 Ships in this PR (`16-slice-5-phase-d-admin-ui`)

- Full UI surface per spec §8.2 (users, tenants, profile, login, home redirect).
- `UiSecurityConfig`, `AuthenticationTenantResolver`, `EmbeddedAuthorizationServerConfig`, `HtmxAuthenticationEntryPoint`.
- UI controllers + form-backing beans + `UiResultMapper` + `UiExceptionHandler`.
- Layout template + page templates + `fragments/`.
- Vendored static assets: `bootstrap.min.css`, `bootstrap-icons.css`, `bootstrap-icons.woff2`, `htmx.min.js`, `keystone-admin.css`.
- `DevUserSeeder` `ApplicationRunner` (profile + property guarded).
- Four new ADRs (0019–0022), each with an Enforcement section.
- ArchUnit rules for ADRs 0019 + 0020 (see §3 and `UiSecurityArchTest`).
- `@WebMvcTest` classes per UI controller + `OAuth2LoginFlowIT` + `AdminUiE2ETest`.
- `application.yaml` additions from §7.5.

### 9.2 Defers to `D-finish` (separate PR)

- Smoke test extensions covering admin JSON API + UI login.
- README status flip on Slice 5.
- Closes #16.
- OpenAPI regen — UI controllers are `@Controller` not `@RestController`, so SpringDoc doesn't touch the snapshot. Sanity-check the diff in this PR anyway.

### 9.3 Non-goals (explicit YAGNI)

- Theming, dark mode, user-selectable palette.
- i18n / localization. All copy is English.
- Remember-me. Session dies with tab.
- Password reset, self-service registration. Users are provisioned by admins.
- Editing a user's `sub` — grant/revoke only.
- Editing a tenant's name — create + soft-delete only.
- Pagination + search. Add when we cross ~100 rows.
- Audit-log UI. `granted_by`/`granted_at` exist; a viewer is a future slice.
- Admin CRUD for accounts / journal entries / periods. Bookkeeper flows stay on the JSON API.
- Multi-tenant admin (admins operating across tenants they don't belong to).
- Progressive enhancement / "works without JS". Admin surface, controlled deployment.
- Rate limiting / brute-force on login — IdP's responsibility.
