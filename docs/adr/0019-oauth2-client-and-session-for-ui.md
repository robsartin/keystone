# ADR-0019: OAuth2 client + session cookie for the admin UI browser flow

- **Status:** Accepted
- **Date:** 2026-07-08

## Context

[ADR-0017](0017-oauth2-resource-server-with-jwt-tenant-claim.md) makes
keystone's API a resource server: callers present a bearer JWT, validated
against an operator-supplied IdP, with tenant identity carried in a
custom claim. That pattern is correct for machine-to-machine and SPA
callers that can hold a token in memory, but the admin UI (Slice 5 Phase
D) is a server-rendered, multi-page browser application. Browser tabs
cannot safely hold a bearer token: there is no storage location (cookie,
`localStorage`, `sessionStorage`) that is both accessible to
same-origin page JavaScript for attaching `Authorization` headers and
safe from XSS exfiltration. The standard, well-understood answer for a
server-rendered app talking to an OIDC-compliant IdP is the OAuth2
Authorization Code flow with PKCE, terminating in a server-side session
identified by an `HttpOnly` cookie — exactly what Spring Security's
`oauth2Login()` support is built for.

The admin UI must authenticate against the *same* IdP the API resource
server trusts (one `issuer-uri` per deployment, one user store), but the
two flows are protocol-incompatible: a resource server validates
self-contained bearer tokens per request and is stateless; a login flow
performs a browser redirect dance and needs server-side session state.
They cannot share one `SecurityFilterChain`.

Tenant identity for the UI must be derived the same way it is for the
API — from the IdP's custom tenant claim — so that the same
`tenant_user_roles` / `platform_admins` data drives authorization on
both surfaces.

## Decision

A second `SecurityFilterChain`, `UiSecurityConfig`, handles the
browser-facing surface: `/admin/ui/**`, `/oauth2/authorization/**`,
`/login/oauth2/code/**`, and `/logout`. It is evaluated at `@Order(2)`,
after the embedded SAS's protocol chains (`@Order(0)`/`@Order(1)`,
`dev`/`test` only) and before `SecurityConfig`'s stateless bearer-JWT
chain (`@Order(3)`) — every request outside the UI's matcher set,
including the entire API surface, falls through untouched.

The chain configures Spring Security's `oauth2Login()` (Authorization
Code + PKCE against the `keystone` client registration) and authenticates
into the servlet container's default session-cookie mechanism —
no custom session store. `AuthenticationTenantResolver` is the same
component `JwtTenantConverter` calls for the bearer-token path: it reads
the configured tenant claim off the `OidcUser`, resolves
`ROLE_PLATFORM_ADMIN` / `ROLE_<tenant-role>` authorities, and populates
the request-scoped `TenantContext` so `RlsTransactionInterceptor` can
`SET LOCAL app.current_tenant`.

Spring's `oidcUserService` callback where that resolution happens fires
exactly once, at the `/login/oauth2/code/**` callback — the resulting
`Authentication` is then cached in the `HttpSession` and replayed
verbatim by `SecurityContextHolderFilter` on every later request.
Because `TenantContext` is `@RequestScope` (a fresh, empty instance per
request), a filter is needed to re-derive it from the cached
authentication on every UI request: `UiTenantContextFilter`, registered
via `addFilterAfter(..., SecurityContextHolderFilter.class)`, does that
by re-invoking `AuthenticationTenantResolver` from the already-cached
`OidcUser`.

An unauthenticated HTMX request receives a `200`-with-`HX-Redirect`
response instead of a full-page redirect (`HtmxAuthenticationEntryPoint`)
so htmx's fetch-based swap can navigate the whole page to `/admin/ui/login`.

## Consequences

**Positive:**

- No bearer token ever reaches browser JavaScript; the session cookie is
  `HttpOnly` and CSRF is mitigated via `CookieCsrfTokenRepository`, the
  conventional combination for this flow.
- The UI and API share one IdP, one tenant-claim contract, and one
  authorization model (`tenant_user_roles`, `platform_admins`) — no
  duplicate user/role plumbing.
- The two chains are fully independent: changes to bearer-JWT validation
  cannot regress the login flow and vice versa.

**Negative:**

- Two `SecurityFilterChain` beans mean two things to reason about when
  debugging auth issues; `@Order` and `securityMatcher()` scoping must
  stay disjoint and correctly ordered relative to the embedded SAS's
  chains (see [ADR-0020](0020-embedded-authorization-server-for-dev-and-test.md)).
  This is documented at length in `UiSecurityConfig`'s class Javadoc.
- Tenant resolution now runs on every UI request (via
  `UiTenantContextFilter`) rather than once at login, trading a small
  per-request DB read for correctness. Acceptable at current UI scale;
  flagged as a caching opportunity if the admin UI grows.
- `oauth2Login()` requires a populated `ClientRegistrationRepository` at
  chain-build time, so `UiSecurityConfig` is profile-gated
  (`@Profile({"dev", "test", "prod"})`) to the same profiles that
  configure the `keystone` client registration — a Spring context that
  boots without one of those profiles (e.g. an unconfigured `local`
  profile) must not wire this chain.
- The cached `Authentication`'s granted authorities are set once at login
  and reused across the session; an admin whose role is revoked mid-session
  retains their `ROLE_*` authorities until logout. `TenantContext` is
  re-derived per request but authority set is not. Acceptable for typical
  admin workflows; force logout is the operator's escape hatch.

## Enforcement

`UiSecurityArchTest.OAUTH2LOGIN_ONLY_IN_UI_SECURITY_CONFIG` asserts that
no class outside `UiSecurityConfig` calls a method named `oauth2Login`
(call-based, via ArchUnit's `callMethodWhere(target(name(...)))`). This
keeps the browser-login wiring from sprawling into a second config class
as the UI grows, which would reintroduce the two-chains-doing-the-same-thing
risk this ADR deliberately avoids.
