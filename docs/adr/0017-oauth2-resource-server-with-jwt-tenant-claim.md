# ADR-0017: OAuth2 resource server with JWT-carried tenant claim

Status: Accepted (2026-05-13)

## Context

Slice 5 needs to authenticate API requests. The options surveyed:

1. **Build our own authorization server** (Spring Authorization Server, user store, refresh tokens, password policy). Largest scope; duplicates work that mature IdPs do better.
2. **Bundle a dev IdP** (Keycloak in `docker-compose.yml`). Production operators bring their own. Adds an extra container.
3. **Resource server only** — keystone validates JWTs against a JWKS endpoint from an external IdP (Auth0, Cognito, Okta, Google, Keycloak — operator's choice). Smallest scope; pushes user management to where it belongs.

For tenant identification, the question is similar: keystone owns user-tenant mapping, or the IdP does?

## Decision

**Keystone is a resource server only.** Operators bring their own OIDC-compliant IdP. Configuration is `keystone.security.issuer-uri` and `keystone.security.audience`.

**Tenant identification comes from a JWT custom claim.** Default claim name is `https://keystone.embracejoy.co/tenant_id` (URI-namespaced per OIDC custom-claim convention; configurable via `keystone.security.tenant-claim`). The claim value is a UUID v7 matching a row in the `tenants` table.

The `JwtTenantConverter` runs as part of the OAuth2 resource-server filter chain. It validates the tenant exists, populates the request-scoped `TenantContext`, looks up the user's role within the tenant from `tenant_user_roles`, and grants `ROLE_<role>` authorities.

A separate `platform_admins` table holds users with the cross-tenant `ROLE_PLATFORM_ADMIN` authority. The bootstrap user is added at startup via the `KEYSTONE_PLATFORM_ADMIN_SUB` env var.

The admin UI uses a separate `SecurityFilterChain` with OAuth2 Authorization Code + PKCE → session cookie (the standard browser-side OAuth2 pattern). Same IdP serves both flows.

## Consequences

- **Positive**: User store, password policy, MFA, social login, etc. are operator's IdP problem. Keystone stays focused on accounting.
- **Positive**: Same IdP serves API + UI. Operators configure one `issuer-uri` and both flows work.
- **Positive**: The custom-claim approach allows one user per token-tenant pair. To switch tenants, get a new token.
- **Negative**: Operators must configure their IdP to emit the tenant claim. We document the requirement and provide an example for Auth0/Keycloak.
- **Negative**: For local dev/tests, we need a way to mint JWTs without standing up a real IdP. Solution: a `JwtTestSupport` helper that signs tokens with a test key, paired with a `NimbusJwtDecoder` configured against the same key in the test profile.
