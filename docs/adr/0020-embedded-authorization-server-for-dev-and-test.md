# ADR-0020: Embedded Spring Authorization Server for dev and test

- **Status:** Accepted
- **Date:** 2026-07-08

## Context

[ADR-0019](0019-oauth2-client-and-session-for-ui.md) wires the admin UI
to a real OAuth2 Authorization Code + PKCE handshake against an
OIDC-compliant IdP, per [ADR-0017](0017-oauth2-resource-server-with-jwt-tenant-claim.md)'s
resource-server-only stance (operators bring their own IdP in
production). Local development and the integration-test suite still
need something to authenticate against — the full redirect dance,
`/oauth2/authorize` → login form → `/oauth2/token` exchange → ID token
with the tenant claim — has to run against *something* for
`OAuth2LoginFlowIT` to exercise the real handshake and for a developer
to click through the login page on `localhost`.

Options considered:

1. **A cloud IdP (Auth0/Cognito/Okta) dev tenant.** Requires a live
   network dependency for every local run and every CI job — unusable
   offline, adds an external-service credential to manage, and makes CI
   flaky against a third party's uptime.
2. **Keycloak via Testcontainers.** A real, production-grade IdP, but a
   JVM-plus-database container adds tens of seconds to every IT run and
   a second moving part (container image, health-check wait) to debug
   when it doesn't come up cleanly.
3. **`@WithMockUser`-only testing.** Fast and dependency-free, but never
   exercises the actual redirect/callback/token-exchange code path —
   the exact code this ADR and ADR-0019 exist to get right. A
   regression in `UiSecurityConfig`'s `oauth2Login()` wiring or in the
   SAS client registration would pass every `@WithMockUser` test and
   still be broken in production.
4. **An embedded Spring Authorization Server, in-process.** Spring
   Security's own `spring-security-oauth2-authorization-server` module
   runs as ordinary `@Configuration` beans inside the same JVM as the
   application under test — no container, no network egress, and it
   speaks the real OIDC protocol.

## Decision

`EmbeddedAuthorizationServerConfig` starts an in-process Spring
Authorization Server, active only on the `dev` and `test` profiles
(`@Profile({"dev", "test"})`). It registers exactly one OAuth2 client,
`keystone-admin-ui`, matching the `keystone` client registration
`UiSecurityConfig` uses — public client (`ClientAuthenticationMethod.NONE`),
authorization-code grant, PKCE required (`ClientSettings.requireProofKey(true)`).

The client is registered with two redirect URIs, because the SAS
enforces exact-match on `redirect_uri` (no wildcard hosts, per the
OAuth2 spec): `http://localhost:8080/login/oauth2/code/keystone` for
local dev (`docker-compose`) and for Maven's `pre-integration-test`
phase (which always pins port 8080), and
`http://localhost:18080/login/oauth2/code/keystone` — the
`EmbeddedAuthorizationServerConfig.TEST_PORT` constant — for
`@SpringBootTest`s that boot at a defined port to avoid colliding with
that pinned 8080 (e.g. `OAuth2LoginFlowIT`).

Three in-memory demo users (`platform@keystone.local`,
`admin@keystone.local`, `bookkeeper@keystone.local`) authenticate via
`formLogin()` on the SAS's own `/login` page. A token customizer
(`tenantClaimCustomizer`) maps their local usernames onto the `sas|*`
subs seeded by `DevUserSeeder` and stamps the same tenant claim
(`https://keystone.embracejoy.co/tenant_id`) onto issued ID tokens that
the bearer-JWT flow expects — so both flows exercise the identical
tenant-resolution code.

Three `SecurityFilterChain` beans coexist for `dev`/`test`:
`sasFormLoginFilterChain` (`@Order(0)`, serves the SAS's `/login` form),
`authorizationServerFilterChain` (`@Order(1)`, the SAS protocol
endpoints), and `UiSecurityConfig`'s `uiFilterChain` (`@Order(2)`).
`SecurityConfig`'s bearer-JWT chain sits at `@Order(3)`.

This configuration is never active in `prod`: production deployments
must set `KEYSTONE_ISSUER_URI` to point at a real, operator-managed IdP.

## Consequences

**Positive:**

- `OAuth2LoginFlowIT` walks the genuine authorization-code + PKCE
  handshake end to end, catching wiring regressions that
  `@WithMockUser` cannot.
- No external network dependency, no container startup latency — the
  embedded SAS starts in milliseconds as part of the same Spring
  context as the app under test.
- Developers get a working `localhost` login without provisioning
  anything.

**Negative:**

- The SAS's in-memory `RegisteredClientRepository` and
  `InMemoryUserDetailsManager` are not persisted; every JVM restart is a
  clean slate, which is the desired behavior for dev/test but means this
  configuration cannot double as a lightweight "demo mode" for a
  long-running deployment.
- Running the client (`keystone-admin-ui`) and the authorization server
  in the same JVM created a self-referential eager-discovery risk during
  Spring Boot's OIDC auto-configuration; `UiSecurityConfig`'s client
  registration deliberately omits `issuer-uri` in favor of explicit
  `authorization-uri`/`token-uri`/`jwk-set-uri` to avoid it triggering
  discovery against itself at boot.
- Two hardcoded redirect ports (`8080`, `TEST_PORT = 18080`) must stay in
  sync with Maven's `pre-integration-test` port pinning and any new
  `@SpringBootTest(webEnvironment = DEFINED_PORT)` test; a mismatch fails
  with an opaque `invalid_redirect_uri` from the SAS rather than a
  compile error.

## Enforcement

`UiSecurityArchTest.SAS_CONFIG_IS_GUARDED_ON_DEV_TEST` asserts (via a
custom `DescribedPredicate<JavaAnnotation<?>>`) that
`EmbeddedAuthorizationServerConfig` carries a `@Profile` annotation whose
`value` array contains BOTH `"dev"` AND `"test"`. Not merely "any
`@Profile`" — a mis-gated `@Profile("prod")` or `@Profile("dev")` alone
would silently pass the presence check while defeating the intent, which
is exactly the regression this rule must catch: an ungated (or wrongly
gated) embedded authorization server booting in `prod` would stand up a
rogue, attacker-discoverable IdP alongside the real one.
