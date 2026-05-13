# Slice 5 — Tenancy + RBAC + OAuth2 Resource Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OAuth2 JWT resource-server authentication, per-tenant data isolation (application filter + Postgres RLS), and tenant-scoped RBAC with three roles (Admin / Bookkeeper / ReadOnly) plus a Platform Admin role above the tenant boundary. Includes admin REST API + minimal Thymeleaf+HTMX UI for managing tenants and user roles.

**Architecture:** Two-layer isolation — request-scoped `TenantContext` bean populated from a JWT custom claim, with Postgres Row-Level Security as the database backstop. Two `DataSource` instances: an app-scoped pool with no RLS bypass for tenant operations, and a `BYPASSRLS`-grant'd platform pool for tenant CRUD. Two `SecurityFilterChain` beans: bearer-JWT for the API surface, OAuth2 Authorization Code + PKCE → session cookie for the UI surface. ADR-0015 codifies "no URL versioning."

**Tech Stack:** Spring Security 6, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-oauth2-client`, `spring-boot-starter-thymeleaf`, vendored HTMX 2.x, vendored Bootstrap CSS. Postgres RLS. Testcontainers Postgres. Spring `JdbcClient` (already in use). Nimbus JOSE+JWT for test JWT minting.

**Spec reference:** [`docs/superpowers/specs/2026-05-13-slice-5-tenancy-and-rbac-design.md`](../specs/2026-05-13-slice-5-tenancy-and-rbac-design.md). Issue [#16](https://github.com/robsartin/keystone/issues/16).

---

## Phase split (4 phases, 4 PRs)

| Phase | Scope | PR title | Approx. commits |
|---|---|---|---|
| A | ADRs + new domain bounded contexts (tenancy + security) + application services. No existing-type changes. | `Slice 5 Phase A: tenancy + security domain types` | ~15 |
| B | V6 migration + tenant_id on existing domain records + adapter updates + two DataSources + RLS + RowLevelSecurityIT + `TenantContext` (populated by a stub Default-Tenant filter for now) | `Slice 5 Phase B: tenant-aware persistence + RLS` | ~15 |
| C | OAuth2 resource server + JwtTenantConverter + SecurityFilterChain (API) + `@PreAuthorize` on every existing controller + SecurityExceptionHandler + JwtTestSupport. Removes the Default-Tenant filter from Phase B. | `Slice 5 Phase C: OAuth2 resource server + RBAC` | ~10 |
| D | Admin API (TenantController, UserRoleController) + UI (Thymeleaf+HTMX, OAuth2 client filter chain) + bootstrap + smoke + OpenAPI regen. Closes #16. | `Slice 5 Phase D: admin API + UI + smoke (closes #16)` | ~12 |

Each phase is a standalone PR; main is green after each merges. Phase B leaves the API unauthenticated but tenant-stamped — the Phase B PR description explicitly notes this interim state.

---

## File structure (master inventory)

### New (Phase A)

```
docs/adr/0015-no-url-versioning.md
docs/adr/0016-multi-tenant-row-level-isolation.md
docs/adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md

src/main/java/.../domain/tenancy/Tenant.java
src/main/java/.../domain/tenancy/TenantId.java
src/main/java/.../domain/tenancy/TenantError.java
src/main/java/.../domain/tenancy/TenantRepository.java

src/main/java/.../domain/security/Role.java
src/main/java/.../domain/security/Permission.java
src/main/java/.../domain/security/UserPrincipal.java
src/main/java/.../domain/security/TenantUserRole.java
src/main/java/.../domain/security/TenantUserRoleRepository.java
src/main/java/.../domain/security/PlatformAdmin.java
src/main/java/.../domain/security/PlatformAdminRepository.java
src/main/java/.../domain/security/SecurityError.java

src/main/java/.../application/tenancy/TenantService.java
src/main/java/.../application/security/UserRoleService.java
```

### New (Phase B)

```
src/main/resources/db/migration/V6__tenancy_and_rbac.sql
docs/operations/setup-platform-role.sql

src/main/java/.../infrastructure/security/TenantContext.java
src/main/java/.../infrastructure/security/DefaultTenantFilter.java     (Phase B stub; removed in Phase C)
src/main/java/.../infrastructure/security/RlsTransactionInterceptor.java

src/main/java/.../infrastructure/persistence/tenancy/JpaTenantRepository.java
src/main/java/.../infrastructure/persistence/tenancy/TenantEntity.java
src/main/java/.../infrastructure/persistence/tenancy/TenantEntityMapper.java
src/main/java/.../infrastructure/persistence/tenancy/TenantRepositoryAdapter.java

src/main/java/.../infrastructure/persistence/security/JpaTenantUserRoleRepository.java
src/main/java/.../infrastructure/persistence/security/TenantUserRoleEntity.java
src/main/java/.../infrastructure/persistence/security/TenantUserRoleEntityMapper.java
src/main/java/.../infrastructure/persistence/security/TenantUserRoleRepositoryAdapter.java
src/main/java/.../infrastructure/persistence/security/JpaPlatformAdminRepository.java
src/main/java/.../infrastructure/persistence/security/PlatformAdminEntity.java
src/main/java/.../infrastructure/persistence/security/PlatformAdminRepositoryAdapter.java

src/main/java/.../infrastructure/config/DataSourcesConfig.java
src/main/java/.../infrastructure/config/KeystoneSecurityProperties.java

src/test/java/.../infrastructure/security/RowLevelSecurityIT.java
```

### Modified (Phase B — the breaking changes)

```
src/main/java/.../domain/account/Account.java               (add TenantId)
src/main/java/.../domain/period/Period.java                 (add TenantId)
src/main/java/.../domain/journal/JournalEntry.java          (add TenantId)
src/main/java/.../domain/journal/JournalValidationContext.java   (carries TenantId)

src/main/java/.../infrastructure/persistence/account/AccountEntity.java
src/main/java/.../infrastructure/persistence/account/AccountEntityMapper.java
src/main/java/.../infrastructure/persistence/account/AccountRepositoryAdapter.java
src/main/java/.../infrastructure/persistence/account/JpaAccountRepository.java

src/main/java/.../infrastructure/persistence/period/PeriodEntity.java
src/main/java/.../infrastructure/persistence/period/PeriodEntityMapper.java
src/main/java/.../infrastructure/persistence/period/PeriodRepositoryAdapter.java
src/main/java/.../infrastructure/persistence/period/JpaPeriodRepository.java

src/main/java/.../infrastructure/persistence/journal/JournalEntryEntity.java
src/main/java/.../infrastructure/persistence/journal/PostingEntity.java
src/main/java/.../infrastructure/persistence/journal/JournalEntryEntityMapper.java
src/main/java/.../infrastructure/persistence/journal/JpaJournalEntryRepository.java

src/main/java/.../infrastructure/persistence/reports/TrialBalanceJdbcReadModel.java   (WHERE p.tenant_id = :tenantId)

src/main/java/.../infrastructure/web/JournalEntryController.java
src/main/java/.../infrastructure/web/account/AccountController.java
src/main/java/.../infrastructure/web/PeriodController.java
src/main/java/.../infrastructure/web/reports/TrialBalanceController.java

src/main/java/.../application/account/AccountService.java
src/main/java/.../application/journal/PostJournalEntryService.java
src/main/java/.../application/period/PeriodService.java
src/main/java/.../application/reports/TrialBalanceService.java

src/main/resources/application.yaml
docker-compose.yml
```

Plus all corresponding test files (~30 fixture updates).

### New (Phase C)

```
src/main/java/.../infrastructure/security/SecurityConfig.java
src/main/java/.../infrastructure/security/JwtTenantConverter.java
src/main/java/.../infrastructure/security/SecurityExceptionHandler.java
src/main/java/.../infrastructure/security/PlatformAdminBootstrap.java

src/test/java/.../infrastructure/security/JwtTestSupport.java          (helper, used by all controller tests)
src/test/java/.../infrastructure/security/JwtTenantConverterTest.java
src/test/java/.../infrastructure/security/SecurityExceptionHandlerTest.java
```

### Modified (Phase C)

```
pom.xml                                               (add 2 starters: security, oauth2-resource-server)
src/main/resources/application.yaml                   (issuer-uri, audience, tenant-claim, bootstrap-platform-admin-sub)

src/main/java/.../infrastructure/security/DefaultTenantFilter.java   (DELETED — replaced by JwtTenantConverter)

All controllers (@PreAuthorize per the permission matrix)
All controller tests (with(jwt(...)) MockMvc setup)
ApplicationSmokeIT (mint JWTs via JwtTestSupport)
```

### New (Phase D)

```
src/main/java/.../infrastructure/web/admin/TenantController.java
src/main/java/.../infrastructure/web/admin/UserRoleController.java
src/main/java/.../infrastructure/web/admin/dto/CreateTenantRequest.java
src/main/java/.../infrastructure/web/admin/dto/TenantResponse.java
src/main/java/.../infrastructure/web/admin/dto/AssignRoleRequest.java
src/main/java/.../infrastructure/web/admin/dto/TenantUserRoleResponse.java

src/main/java/.../infrastructure/web/ui/AdminUiController.java
src/main/java/.../infrastructure/web/ui/UsersUiController.java
src/main/java/.../infrastructure/web/ui/TenantsUiController.java
src/main/java/.../infrastructure/web/ui/ProfileUiController.java
src/main/java/.../infrastructure/security/UiSecurityConfig.java        (separate filter chain; oauth2Login)

src/main/resources/templates/admin/layout.html
src/main/resources/templates/admin/login.html
src/main/resources/templates/admin/users.html
src/main/resources/templates/admin/tenants.html
src/main/resources/templates/admin/tenant-detail.html
src/main/resources/templates/admin/profile.html
src/main/resources/templates/admin/fragments/user-row.html
src/main/resources/templates/admin/fragments/tenant-row.html

src/main/resources/static/htmx.min.js                                  (vendored)
src/main/resources/static/bootstrap.min.css                            (vendored)
```

### Modified (Phase D)

```
pom.xml                                               (add 2 starters: thymeleaf, oauth2-client)
src/main/resources/application.yaml                   (oauth2 client registration)
src/test/java/.../smoke/ApplicationSmokeIT.java       (auth-protected smoke + 2-tenant isolation)
docs/openapi/openapi.yaml                             (regen with admin endpoints)
README.md                                             (Slice 5 ✅, Auth section)
CLAUDE.md                                             (Tenancy + auth conventions bullet)
```

---

# Phase A — Foundation (ADRs + new domain types + application services)

Phase A introduces the ADRs and the new bounded contexts (tenancy, security). No existing types change. The build stays green throughout. ~15 commits.

This is the simplest phase to TDD because everything is new and isolated.

---

## Task A1: ADR-0015 — no URL versioning

**Files:**
- Create: `docs/adr/0015-no-url-versioning.md`

- [ ] **Step 1: Read the existing ADR template**

```bash
cat docs/adr/0000-template.md
```

This shows the ADR structure (Status, Context, Decision, Consequences).

- [ ] **Step 2: Write the ADR**

Create `docs/adr/0015-no-url-versioning.md`:

```markdown
# ADR-0015: No URL versioning

Status: Accepted (2026-05-13)

## Context

Slice 5 makes every endpoint security-gated, which is a breaking change to the wire contract. With breaking changes on the table, the question naturally arises: should we add `/v1/` prefixes to URLs to allow side-by-side coexistence of old and new shapes?

The argument for URL versioning is "clients can opt in." The arguments against:

- URL versioning permanently forks every endpoint. Maintaining `/v1/accounts` and `/v2/accounts` doubles the controller surface, the OpenAPI spec, the integration tests, and the documentation.
- It creates an ambiguous source of truth: "what's the current version?" becomes a perennial question.
- Real version-skew problems are usually solved by feature flags or response shape evolution (additive fields), not by parallel URL trees.
- Industry practice has moved away from URL versioning for newer APIs (Stripe, Slack, GitHub all use header-based or implicit versioning today).

## Decision

Keystone APIs are versionless at the URL level. There will never be `/v1/...` or `/v2/...` paths.

Breaking changes are caught at PR time by the four-layer OpenAPI gate (Spectral lint + snapshot diff + openapi-diff vs `main` + the `breaking-change-approved` label flow established in [ADR-0006](0006-openapi-gates.md) and exercised in Slice 6 Phase C). When unavoidable, breaking changes are announced in release notes and an ADR.

If a versioned compatibility window ever becomes necessary later, it will be header-based (`API-Version: YYYY-MM-DD`, Stripe-style) — never URL-prefixed.

## Consequences

- **Positive**: One canonical URL per resource; no doubled controllers; no ambiguity.
- **Positive**: The `breaking-change-approved` PR label is the explicit, reviewed gate for any wire-contract change.
- **Negative**: Operators must coordinate client upgrades with server upgrades. There's no "leave the old version running for a quarter" escape hatch.
- **Mitigation**: Deprecation warnings via response headers (`Deprecation`, `Sunset` per RFC 8594) for known-breaking transitions, with at least one minor release of warning time before the breaking version ships.
```

- [ ] **Step 3: Update ADR README**

Append to `docs/adr/README.md` (read it first to see the format):

```markdown
| 0015 | [No URL versioning](0015-no-url-versioning.md) | Accepted |
```

If the README uses a different format, follow that format instead.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0015-no-url-versioning.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-0015 — no URL versioning

Codifies the policy that keystone URLs are never prefixed with
/v1/ or /v2/. Breaking changes go through the four-layer OpenAPI
gate (Spectral + snapshot diff + openapi-diff vs main + the
breaking-change-approved label). If a versioned compatibility
window ever becomes necessary, it'll be header-based (API-Version:
YYYY-MM-DD, Stripe-style) — never URL-prefixed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A2: ADR-0016 — multi-tenant row-level isolation

**Files:**
- Create: `docs/adr/0016-multi-tenant-row-level-isolation.md`

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0016-multi-tenant-row-level-isolation.md`:

```markdown
# ADR-0016: Multi-tenant row-level isolation with Postgres RLS

Status: Accepted (2026-05-13)

## Context

Slice 5 introduces multi-tenancy. The keystone serves multiple tenants from a single deployment (with a clean single-tenant standalone fallback). Three isolation strategies were considered:

1. **Schema-per-tenant**: each tenant gets its own Postgres schema. Per-tenant Flyway runs; per-tenant connection pool overhead. Doesn't scale past hundreds of tenants. Operational complexity (e.g., bulk migrations across N schemas).
2. **Database-per-tenant**: each tenant gets its own database. Strongest isolation; worst operational story for a single-deployment SaaS.
3. **Row-level (`tenant_id` column on every business table)**: simplest schema; scales to millions of tenants; risk is that a single forgotten `WHERE tenant_id = ?` leaks data across tenants.

For a financial ledger, cross-tenant data leakage is catastrophic — clients would never trust the system again. We want a defense-in-depth solution.

## Decision

Use row-level isolation with `tenant_id UUID NOT NULL` on every business table, **enforced by both layers**:

- **Application filter**: a request-scoped `TenantContext` bean is populated from the JWT custom claim. Repository adapters read it and apply `WHERE tenant_id = ?` to every query and validate it on every write.
- **Postgres Row-Level Security**: every tenant-scoped table has an RLS policy (`USING tenant_id = current_setting('app.current_tenant', true)::uuid`). A transaction interceptor sets the GUC at the start of each transaction. Policies use `WITH CHECK` so RLS rejects misrouted writes too.

For platform-admin operations that span tenants (tenant CRUD), a separate Postgres role `keystone_platform` with `BYPASSRLS` granted is used — connected via a second `DataSource` and a separate JPA `EntityManagerFactory`.

## Consequences

- **Positive**: Defense in depth. A bug in application filtering still can't leak data — RLS blocks it. A bug in RLS setup is caught by the application filter and the dedicated `RowLevelSecurityIT`.
- **Positive**: Account codes and period year-months become unique per tenant (composite primary keys), which is the natural mental model for accounting systems.
- **Negative**: Every existing repository, entity, mapper, and test fixture changes. ~80 sites updated.
- **Negative**: Two `DataSource`s and two `EntityManagerFactory`s — more boot config.
- **Mitigation**: ArchUnit rules enforce that every adapter reads `TenantContext` (or takes `TenantId` as a parameter). The centerpiece `RowLevelSecurityIT` exercises both layers in isolation and together.
```

- [ ] **Step 2: Update ADR README**

Append:

```markdown
| 0016 | [Multi-tenant row-level isolation with Postgres RLS](0016-multi-tenant-row-level-isolation.md) | Accepted |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0016-multi-tenant-row-level-isolation.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-0016 — multi-tenant row-level isolation with Postgres RLS

Tenant_id on every business table; defense in depth via app filter
+ Postgres RLS. Two DataSources for the BYPASSRLS escape hatch
(platform-admin tenant CRUD). Documents the trade-offs vs
schema-per-tenant and database-per-tenant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A3: ADR-0017 — OAuth2 resource server + JWT tenant claim

**Files:**
- Create: `docs/adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md`

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md`:

```markdown
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
```

- [ ] **Step 2: Update ADR README**

```markdown
| 0017 | [OAuth2 resource server with JWT-carried tenant claim](0017-oauth2-resource-server-with-jwt-tenant-claim.md) | Accepted |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): ADR-0017 — OAuth2 resource server with JWT tenant claim

Keystone is a resource server; operators bring their own OIDC IdP.
Tenant ID comes from a JWT custom claim (URI-namespaced, configurable).
Two security filter chains: bearer JWT for the API, OAuth2 Authorization
Code + PKCE → session cookie for the UI. Separate platform_admins table
for cross-tenant authority.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A4: `TenantId` typed wrapper

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantId.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantIdTest.java`

Pattern reference: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryId.java` is the existing typed UUID wrapper precedent.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantIdTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantId")
class TenantIdTest {

  @Test
  @DisplayName("wraps a non-null UUID")
  void shouldWrapUuid() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    TenantId tenantId = new TenantId(uuid);
    assertEquals(uuid, tenantId.value());
  }

  @Test
  @DisplayName("rejects null UUID")
  void shouldThrowWhenUuidIsNull() {
    assertThrows(NullPointerException.class, () -> new TenantId(null));
  }

  @Test
  @DisplayName("equal when wrapping the same UUID")
  void shouldEqualWhenSameUuid() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    assertEquals(new TenantId(uuid), new TenantId(uuid));
  }

  @Test
  @DisplayName("not equal when wrapping different UUIDs")
  void shouldNotEqualWhenDifferentUuid() {
    assertNotEquals(
        new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000")),
        new TenantId(UUID.fromString("01902fa0-0000-7000-8000-000000000000")));
  }

  @Test
  @DisplayName("toString includes the UUID value (debuggability)")
  void shouldIncludeUuidInToString() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    assertEquals("TenantId[value=" + uuid + "]", new TenantId(uuid).toString());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./mvnw -B test -Dtest=TenantIdTest 2>&1 | tail -10
```

Expected: compile failure (`TenantId` does not exist).

- [ ] **Step 3: Create the record**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantId.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

import java.util.Objects;
import java.util.UUID;

/** Typed wrapper around the {@link UUID} primary key of a {@code Tenant}. */
public record TenantId(UUID value) {

  public TenantId {
    Objects.requireNonNull(value, "value");
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -B test -Dtest=TenantIdTest 2>&1 | tail -10
```

Expected: 5 tests pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantId.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantIdTest.java
git commit -m "$(cat <<'EOF'
feat(domain): TenantId typed UUID wrapper

Mirrors JournalEntryId — a single-field record around UUID, with
non-null invariant. Carried as the first field on every tenant-scoped
domain aggregate (Account, JournalEntry, Period, etc.) once Phase B
lands.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A5: `Tenant` record + `TenantError` sealed

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/Tenant.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantError.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tenant")
class TenantTest {

  private static final TenantId ID =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final Instant CREATED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with id, name, createdAt; deactivatedAt absent")
  void shouldConstructActiveTenant() {
    Tenant t = new Tenant(ID, "Acme Corp", CREATED, Optional.empty());
    assertEquals(ID, t.id());
    assertEquals("Acme Corp", t.name());
    assertEquals(CREATED, t.createdAt());
    assertTrue(t.isActive());
    assertFalse(t.isDeactivated());
  }

  @Test
  @DisplayName("isDeactivated() true when deactivatedAt is present")
  void shouldReportDeactivated() {
    Instant deactivated = Instant.parse("2026-06-01T12:00:00Z");
    Tenant t = new Tenant(ID, "Acme Corp", CREATED, Optional.of(deactivated));
    assertFalse(t.isActive());
    assertTrue(t.isDeactivated());
  }

  @Test
  @DisplayName("rejects null id")
  void shouldThrowWhenIdNull() {
    assertThrows(
        NullPointerException.class, () -> new Tenant(null, "Acme", CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null name")
  void shouldThrowWhenNameNull() {
    assertThrows(
        NullPointerException.class, () -> new Tenant(ID, null, CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects blank name")
  void shouldThrowWhenNameBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Tenant(ID, "  ", CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null createdAt")
  void shouldThrowWhenCreatedAtNull() {
    assertThrows(
        NullPointerException.class, () -> new Tenant(ID, "Acme", null, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null deactivatedAt Optional")
  void shouldThrowWhenDeactivatedAtOptionalNull() {
    assertThrows(NullPointerException.class, () -> new Tenant(ID, "Acme", CREATED, null));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw -B test -Dtest=TenantTest 2>&1 | tail -10
```

Expected: compile failure.

- [ ] **Step 3: Create the `Tenant` record**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/Tenant.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A tenant — the boundary of data isolation. Every business row carries a {@link TenantId}.
 *
 * <p>Soft delete: setting {@link #deactivatedAt()} marks the tenant as inactive without
 * removing its data. The composite foreign keys on {@code accounts}/{@code periods}/etc. mean
 * a hard delete would cascade through years of accounting data; that path is intentionally
 * not exposed.
 */
public record Tenant(
    TenantId id, String name, Instant createdAt, Optional<Instant> deactivatedAt) {

  public Tenant {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(deactivatedAt, "deactivatedAt");
  }

  public boolean isActive() {
    return deactivatedAt.isEmpty();
  }

  public boolean isDeactivated() {
    return deactivatedAt.isPresent();
  }
}
```

- [ ] **Step 4: Create `TenantError`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantError.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

/**
 * Sealed errors produced by tenant operations. Mapped to ProblemDetail at the HTTP boundary by
 * {@code SecurityExceptionHandler} (Phase C).
 */
public sealed interface TenantError {

  /** No tenant exists with the given id. */
  record NotFound(TenantId id) implements TenantError {}

  /** A name was supplied but failed validation (e.g., blank). */
  record InvalidName(String reason) implements TenantError {}

  /** Attempted to operate on a deactivated tenant where active is required. */
  record Deactivated(TenantId id) implements TenantError {}
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
./mvnw -B test -Dtest=TenantTest 2>&1 | tail -10
```

Expected: 7 tests pass.

- [ ] **Step 6: Commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/Tenant.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantError.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantTest.java
git commit -m "$(cat <<'EOF'
feat(domain): Tenant record + TenantError sealed

Tenant carries id, name, createdAt, optional deactivatedAt (soft
delete only). Compact constructor enforces non-null + non-blank-name.
isActive()/isDeactivated() convenience accessors.

TenantError is a sealed interface with NotFound, InvalidName,
Deactivated variants — mapped to ProblemDetail at the HTTP boundary
in Phase C.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A6: `TenantRepository` port

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantRepository.java`

- [ ] **Step 1: Create the port**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantRepository.java`:

```java
package co.embracejoy.accounting.keystone.domain.tenancy;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Tenant}.
 *
 * <p>Wired in Phase B (Spring `BYPASSRLS` `DataSource` for the implementation; tenants table is
 * itself not RLS-protected).
 */
public interface TenantRepository {

  /** Persist a new tenant. Returns the saved aggregate. */
  Result<Tenant, TenantError> save(Tenant tenant);

  /** Look up a tenant by id. Returns Optional.empty() if not found (active or not). */
  Optional<Tenant> findById(TenantId id);

  /** All tenants (active + deactivated). Ordered by createdAt ASC. */
  List<Tenant> findAll();

  /** Soft-delete: sets {@code deactivatedAt} to now. Idempotent. */
  Result<Tenant, TenantError> deactivate(TenantId id);
}
```

No test file for the port itself — the contract is tested via the service tests (with a fake) and the IT (with the JPA adapter).

- [ ] **Step 2: Compile check**

```bash
./mvnw -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/co/embracejoy/accounting/keystone/domain/tenancy/TenantRepository.java
git commit -m "$(cat <<'EOF'
feat(domain): TenantRepository port

Four operations: save, findById, findAll, deactivate. No hard
delete — the composite foreign keys on tenant-scoped tables mean
a true delete would cascade through years of data. Operators who
truly want to purge run a separate SQL script.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A7: `Role` + `Permission` enums

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/Permission.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/Role.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/security/RoleTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/security/RoleTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Role")
class RoleTest {

  @Test
  @DisplayName("READ_ONLY has read-only permissions")
  void shouldGrantReadOnlyPermissions() {
    Set<Permission> perms = Role.READ_ONLY.permissions();
    assertThat(perms)
        .containsExactlyInAnyOrder(
            Permission.ACCOUNT_READ, Permission.PERIOD_READ, Permission.REPORT_READ);
  }

  @Test
  @DisplayName("BOOKKEEPER has read + day-to-day write permissions, not structural changes")
  void shouldGrantBookkeeperPermissions() {
    Set<Permission> perms = Role.BOOKKEEPER.permissions();
    assertThat(perms)
        .containsExactlyInAnyOrder(
            Permission.ACCOUNT_READ,
            Permission.ACCOUNT_WRITE,
            Permission.JOURNAL_POST,
            Permission.PERIOD_READ,
            Permission.REPORT_READ);
  }

  @Test
  @DisplayName("ADMIN has all tenant-scoped permissions")
  void shouldGrantAdminAllTenantPermissions() {
    Set<Permission> perms = Role.ADMIN.permissions();
    assertThat(perms)
        .containsExactlyInAnyOrder(
            Permission.ACCOUNT_READ,
            Permission.ACCOUNT_WRITE,
            Permission.ACCOUNT_DEACTIVATE,
            Permission.JOURNAL_POST,
            Permission.PERIOD_READ,
            Permission.PERIOD_CLOSE,
            Permission.REPORT_READ,
            Permission.TENANT_USER_MANAGE);
  }

  @Test
  @DisplayName("permission sets are immutable")
  void shouldReturnUnmodifiablePermissionSet() {
    Set<Permission> perms = Role.ADMIN.permissions();
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> perms.add(Permission.ACCOUNT_READ));
  }

  @Test
  @DisplayName("ADMIN strictly contains all BOOKKEEPER permissions")
  void shouldHaveAdminContainBookkeeperPermissions() {
    assertThat(Role.ADMIN.permissions()).containsAll(Role.BOOKKEEPER.permissions());
  }

  @Test
  @DisplayName("BOOKKEEPER strictly contains all READ_ONLY permissions")
  void shouldHaveBookkeeperContainReadOnlyPermissions() {
    assertThat(Role.BOOKKEEPER.permissions()).containsAll(Role.READ_ONLY.permissions());
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw -B test -Dtest=RoleTest 2>&1 | tail -10
```

Expected: compile failure.

- [ ] **Step 3: Create `Permission`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/security/Permission.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

/**
 * Granular permissions checked at the controller layer via {@code @PreAuthorize} (Phase C).
 *
 * <p>Permissions are bundled into {@link Role}s. The names group by aggregate:
 *
 * <ul>
 *   <li>{@code ACCOUNT_*} — chart-of-accounts operations
 *   <li>{@code JOURNAL_POST} — posting balanced entries
 *   <li>{@code PERIOD_*} — period state changes
 *   <li>{@code REPORT_READ} — trial balance, future reports
 *   <li>{@code TENANT_USER_MANAGE} — assign/revoke roles within the tenant
 * </ul>
 *
 * <p>Platform-admin permissions (creating tenants, etc.) are gated by the {@code
 * ROLE_PLATFORM_ADMIN} authority directly, not by this enum.
 */
public enum Permission {
  ACCOUNT_READ,
  ACCOUNT_WRITE,
  ACCOUNT_DEACTIVATE,
  JOURNAL_POST,
  PERIOD_READ,
  PERIOD_CLOSE,
  REPORT_READ,
  TENANT_USER_MANAGE
}
```

- [ ] **Step 4: Create `Role`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/security/Role.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import java.util.Set;

/**
 * Three tenant-scoped roles, each with a fixed bundle of {@link Permission}s. Per the spec
 * (§7.4 permission matrix):
 *
 * <ul>
 *   <li>{@code READ_ONLY} — auditors / executives. Read books only.
 *   <li>{@code BOOKKEEPER} — daily entries + create accounts; cannot deactivate accounts or
 *       close periods.
 *   <li>{@code ADMIN} — controller / CFO. Period close/reopen, account deactivation, user
 *       management within the tenant.
 * </ul>
 *
 * <p>Platform admins live above the tenant boundary in the {@code platform_admins} table.
 */
public enum Role {
  READ_ONLY(Set.of(Permission.ACCOUNT_READ, Permission.PERIOD_READ, Permission.REPORT_READ)),

  BOOKKEEPER(
      Set.of(
          Permission.ACCOUNT_READ,
          Permission.ACCOUNT_WRITE,
          Permission.JOURNAL_POST,
          Permission.PERIOD_READ,
          Permission.REPORT_READ)),

  ADMIN(
      Set.of(
          Permission.ACCOUNT_READ,
          Permission.ACCOUNT_WRITE,
          Permission.ACCOUNT_DEACTIVATE,
          Permission.JOURNAL_POST,
          Permission.PERIOD_READ,
          Permission.PERIOD_CLOSE,
          Permission.REPORT_READ,
          Permission.TENANT_USER_MANAGE));

  private final Set<Permission> permissions;

  Role(Set<Permission> permissions) {
    this.permissions = permissions; // Set.of() is already immutable
  }

  public Set<Permission> permissions() {
    return permissions;
  }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
./mvnw -B test -Dtest=RoleTest 2>&1 | tail -10
```

Expected: 6 tests pass.

- [ ] **Step 6: Commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/security/Permission.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/security/Role.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/security/RoleTest.java
git commit -m "$(cat <<'EOF'
feat(domain): Role + Permission enums (3 roles, 8 permissions)

Per spec §7.4: READ_ONLY ⊂ BOOKKEEPER ⊂ ADMIN (each role's
permission set is a strict superset of the previous). Phase C wires
@PreAuthorize on every controller; the role-to-permission mapping
is the single source of truth.

Tests assert each role's exact permission set, the immutability
of the returned Set, and the containment hierarchy.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A8: `UserPrincipal` + `TenantUserRole` + `PlatformAdmin` records

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipal.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRole.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdmin.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipalTest.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRoleTest.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdminTest.java`

- [ ] **Step 1: Write `UserPrincipalTest`**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipalTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserPrincipal")
class UserPrincipalTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final String SUB = "auth0|abc123";

  @Test
  @DisplayName("constructs with sub, tenantId, role, platformAdmin flag")
  void shouldConstruct() {
    UserPrincipal p = new UserPrincipal(SUB, Optional.of(TENANT), Optional.of(Role.ADMIN), false);
    assertEquals(SUB, p.sub());
    assertEquals(Optional.of(TENANT), p.tenantId());
    assertEquals(Optional.of(Role.ADMIN), p.role());
    assertEquals(false, p.platformAdmin());
  }

  @Test
  @DisplayName("rejects null sub")
  void shouldThrowWhenSubNull() {
    assertThrows(
        NullPointerException.class,
        () -> new UserPrincipal(null, Optional.of(TENANT), Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects blank sub")
  void shouldThrowWhenSubBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UserPrincipal("   ", Optional.of(TENANT), Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects null tenantId Optional")
  void shouldThrowWhenTenantOptionalNull() {
    assertThrows(
        NullPointerException.class,
        () -> new UserPrincipal(SUB, null, Optional.of(Role.ADMIN), false));
  }

  @Test
  @DisplayName("rejects null role Optional")
  void shouldThrowWhenRoleOptionalNull() {
    assertThrows(
        NullPointerException.class, () -> new UserPrincipal(SUB, Optional.of(TENANT), null, false));
  }

  @Test
  @DisplayName("platform-admin-only principal: no tenant, no role")
  void shouldAcceptPlatformAdminWithoutTenantOrRole() {
    UserPrincipal p = new UserPrincipal(SUB, Optional.empty(), Optional.empty(), true);
    assertTrue(p.platformAdmin());
  }
}
```

- [ ] **Step 2: Run to fail**

```bash
./mvnw -B test -Dtest=UserPrincipalTest 2>&1 | tail -10
```

- [ ] **Step 3: Create `UserPrincipal`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipal.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Objects;
import java.util.Optional;

/**
 * The authenticated user as observed by the application layer.
 *
 * <p>{@code sub} is the IdP-issued subject claim ({@code jwt.getSubject()}). {@code tenantId}
 * is the tenant from the JWT custom claim (empty if the user authenticated as a platform admin
 * without a tenant). {@code role} is the user's role within that tenant (empty if no row in
 * {@code tenant_user_roles}). {@code platformAdmin} is true if the {@code sub} is in the
 * {@code platform_admins} table.
 */
public record UserPrincipal(
    String sub, Optional<TenantId> tenantId, Optional<Role> role, boolean platformAdmin) {

  public UserPrincipal {
    Objects.requireNonNull(sub, "sub");
    if (sub.isBlank()) {
      throw new IllegalArgumentException("sub must not be blank");
    }
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(role, "role");
  }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=UserPrincipalTest 2>&1 | tail -10
```

Expected: 6 pass.

- [ ] **Step 5: Write `TenantUserRoleTest`**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRoleTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantUserRole")
class TenantUserRoleTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final Instant GRANTED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with tenant, sub, role, grantedAt, grantedBy")
  void shouldConstruct() {
    TenantUserRole r = new TenantUserRole(TENANT, "auth0|user", Role.BOOKKEEPER, GRANTED, "auth0|admin");
    assertEquals(TENANT, r.tenantId());
    assertEquals("auth0|user", r.userSub());
    assertEquals(Role.BOOKKEEPER, r.role());
    assertEquals(GRANTED, r.grantedAt());
    assertEquals("auth0|admin", r.grantedBy());
  }

  @Test
  @DisplayName("rejects null tenantId")
  void shouldThrowWhenTenantNull() {
    assertThrows(
        NullPointerException.class,
        () -> new TenantUserRole(null, "u", Role.ADMIN, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects blank userSub")
  void shouldThrowWhenUserSubBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantUserRole(TENANT, " ", Role.ADMIN, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects null role")
  void shouldThrowWhenRoleNull() {
    assertThrows(
        NullPointerException.class,
        () -> new TenantUserRole(TENANT, "u", null, GRANTED, "g"));
  }

  @Test
  @DisplayName("rejects blank grantedBy")
  void shouldThrowWhenGrantedByBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantUserRole(TENANT, "u", Role.ADMIN, GRANTED, ""));
  }
}
```

- [ ] **Step 6: Create `TenantUserRole`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRole.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * One user's role within one tenant. Persisted in {@code tenant_user_roles} with composite
 * primary key {@code (tenant_id, user_sub)}.
 */
public record TenantUserRole(
    TenantId tenantId, String userSub, Role role, Instant grantedAt, String grantedBy) {

  public TenantUserRole {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(userSub, "userSub");
    if (userSub.isBlank()) {
      throw new IllegalArgumentException("userSub must not be blank");
    }
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(grantedAt, "grantedAt");
    Objects.requireNonNull(grantedBy, "grantedBy");
    if (grantedBy.isBlank()) {
      throw new IllegalArgumentException("grantedBy must not be blank");
    }
  }
}
```

- [ ] **Step 7: Write `PlatformAdminTest`**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdminTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformAdmin")
class PlatformAdminTest {

  private static final Instant GRANTED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with userSub and grantedAt")
  void shouldConstruct() {
    PlatformAdmin p = new PlatformAdmin("auth0|root", GRANTED);
    assertEquals("auth0|root", p.userSub());
    assertEquals(GRANTED, p.grantedAt());
  }

  @Test
  @DisplayName("rejects blank userSub")
  void shouldThrowWhenUserSubBlank() {
    assertThrows(IllegalArgumentException.class, () -> new PlatformAdmin("", GRANTED));
  }

  @Test
  @DisplayName("rejects null grantedAt")
  void shouldThrowWhenGrantedAtNull() {
    assertThrows(NullPointerException.class, () -> new PlatformAdmin("u", null));
  }
}
```

- [ ] **Step 8: Create `PlatformAdmin`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdmin.java`:

```java
package co.embracejoy.accounting.keystone.domain.security;

import java.time.Instant;
import java.util.Objects;

/**
 * A user with cross-tenant authority. Stored in the {@code platform_admins} table (PK on
 * {@code user_sub}). Platform admins create tenants and grant the platform-admin role to
 * other users via SQL or the bootstrap env var; they do not get implicit access to tenant
 * data — they need an explicit role in {@code tenant_user_roles} for that.
 */
public record PlatformAdmin(String userSub, Instant grantedAt) {

  public PlatformAdmin {
    Objects.requireNonNull(userSub, "userSub");
    if (userSub.isBlank()) {
      throw new IllegalArgumentException("userSub must not be blank");
    }
    Objects.requireNonNull(grantedAt, "grantedAt");
  }
}
```

- [ ] **Step 9: Run all three tests**

```bash
./mvnw -B test -Dtest=UserPrincipalTest,TenantUserRoleTest,PlatformAdminTest 2>&1 | tail -10
```

Expected: 14 tests pass.

- [ ] **Step 10: Commit**

```bash
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipal.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRole.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdmin.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/security/UserPrincipalTest.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRoleTest.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdminTest.java
git commit -m "$(cat <<'EOF'
feat(domain): UserPrincipal + TenantUserRole + PlatformAdmin records

UserPrincipal is the application-layer view of an authenticated user
— sub + optional tenantId + optional role + boolean platformAdmin.
Optional tenant/role accommodates platform admins who don't carry a
tenant claim.

TenantUserRole is one row of (tenant, user, role, grantedAt, grantedBy).
PK in storage is (tenant_id, user_sub) per spec §5.1.

PlatformAdmin is a user with cross-tenant authority — stored in the
platform_admins table, never RLS-protected.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task A9: `TenantUserRoleRepository` + `PlatformAdminRepository` ports

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRoleRepository.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdminRepository.java`

- [ ] **Step 1: Create `TenantUserRoleRepository`**

```java
package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link TenantUserRole}. */
public interface TenantUserRoleRepository {

  /**
   * Insert or update the role. Idempotent: granting the same role to the same user is a
   * no-op that returns the existing row.
   */
  TenantUserRole grant(TenantUserRole assignment);

  /** Look up a user's role within a tenant. Returns {@code Optional.empty()} if no row. */
  Optional<TenantUserRole> findRole(TenantId tenantId, String userSub);

  /** All role assignments within a tenant, ordered by {@code grantedAt} ASC. */
  List<TenantUserRole> findByTenant(TenantId tenantId);

  /** Remove the user's role within the tenant. Returns true if a row was removed. */
  boolean revoke(TenantId tenantId, String userSub);

  /**
   * Count of users currently holding {@link Role#ADMIN} in this tenant. Used by
   * {@code UserRoleService} to enforce the can't-orphan-self rule.
   */
  long countAdmins(TenantId tenantId);
}
```

- [ ] **Step 2: Create `PlatformAdminRepository`**

```java
package co.embracejoy.accounting.keystone.domain.security;

import java.util.List;
import java.util.Optional;

/** Persistence port for {@link PlatformAdmin}. */
public interface PlatformAdminRepository {

  /** Insert a platform admin. Idempotent: re-grant returns the existing row. */
  PlatformAdmin grant(String userSub);

  /** Look up by sub. */
  Optional<PlatformAdmin> findBySub(String userSub);

  /** True iff the user is a platform admin. Cheap existence check used per request. */
  boolean exists(String userSub);

  /** All platform admins, ordered by grantedAt ASC. */
  List<PlatformAdmin> findAll();
}
```

- [ ] **Step 3: Compile + commit**

Run:
```
./mvnw -B -q compile
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/security/TenantUserRoleRepository.java \
        src/main/java/co/embracejoy/accounting/keystone/domain/security/PlatformAdminRepository.java
git commit -m "feat(domain): TenantUserRoleRepository + PlatformAdminRepository ports"
```

(Use the same HEREDOC commit-message pattern as Task A1 with the body explaining: TenantUserRoleRepository has grant/findRole/findByTenant/revoke/countAdmins — countAdmins exists for the can't-orphan-self rule. PlatformAdminRepository has grant/findBySub/exists/findAll — exists is the per-request fast path used by JwtTenantConverter.)

---

## Task A10: `SecurityError` sealed

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/security/SecurityError.java`

- [ ] **Step 1: Create the sealed type**

```java
package co.embracejoy.accounting.keystone.domain.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;

/**
 * Sealed errors produced by security operations. Mapped to RFC 9457 ProblemDetail at the HTTP
 * boundary by {@code SecurityExceptionHandler} (Phase C). The HTTP status for each variant is
 * documented inline.
 */
public sealed interface SecurityError {

  /** A user role assignment was looked up but not found. → 404. */
  record RoleNotFound(TenantId tenantId, String userSub) implements SecurityError {}

  /**
   * The lone tenant Admin attempted to demote themselves. The tenant would have zero admins.
   * → 400 {@code /problems/admin/cannot-orphan-self}.
   */
  record CannotOrphanSelf(TenantId tenantId, String userSub) implements SecurityError {}

  /** The current request has no usable tenant in context. → 403. */
  record MissingTenant() implements SecurityError {}

  /** The JWT carried a tenant claim referencing a non-existent tenant. → 403. */
  record UnknownTenant(TenantId tenantId) implements SecurityError {}

  /** The current user lacks the required role/permission for the endpoint. → 403. */
  record InsufficientRole(String required) implements SecurityError {}
}
```

- [ ] **Step 2: Compile + commit**

Apply Spotless and commit with body: "Five variants matching spec §7.5: RoleNotFound (404), CannotOrphanSelf (400), MissingTenant (403), UnknownTenant (403), InsufficientRole (403). Phase C's SecurityExceptionHandler maps each to a stable ProblemDetail URI."

---

## Task A11: `TenantService`

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/tenancy/TenantService.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/tenancy/TenantServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `TenantServiceTest.java`:

```java
package co.embracejoy.accounting.keystone.application.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantService")
class TenantServiceTest {

  private static final Instant FIXED_TIME = Instant.parse("2026-05-13T10:00:00Z");

  private FakeTenantRepository repo;
  private FakeUuidSupplier uuids;
  private TenantService service;

  @BeforeEach
  void setup() {
    repo = new FakeTenantRepository();
    uuids = new FakeUuidSupplier();
    Clock clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    service = new TenantService(repo, clock, uuids);
  }

  @Test
  @DisplayName("create() saves a tenant with a fresh UUID, name, createdAt=now, no deactivatedAt")
  void shouldCreateTenant() {
    Result<Tenant, TenantError> r = service.create("Acme Corp");
    assertThat(r).isInstanceOf(Result.Success.class);
    Tenant t = ((Result.Success<Tenant, TenantError>) r).value();
    assertThat(t.name()).isEqualTo("Acme Corp");
    assertThat(t.createdAt()).isEqualTo(FIXED_TIME);
    assertThat(t.deactivatedAt()).isEmpty();
    assertThat(repo.byId).hasSize(1);
  }

  @Test
  @DisplayName("create() returns Failure(InvalidName) for blank name")
  void shouldReturnInvalidNameWhenBlank() {
    Result<Tenant, TenantError> r = service.create("   ");
    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Tenant, TenantError>) r).error())
        .isInstanceOf(TenantError.InvalidName.class);
  }

  @Test
  @DisplayName("findById returns the saved tenant")
  void shouldFindById() {
    Tenant created = ((Result.Success<Tenant, TenantError>) service.create("Acme")).value();
    Optional<Tenant> found = service.findById(created.id());
    assertThat(found).contains(created);
  }

  @Test
  @DisplayName("findAll returns all created tenants in insertion order")
  void shouldFindAll() {
    service.create("First");
    service.create("Second");
    List<Tenant> all = service.findAll();
    assertThat(all).extracting(Tenant::name).containsExactly("First", "Second");
  }

  @Test
  @DisplayName("deactivate sets deactivatedAt; subsequent finds show it deactivated")
  void shouldDeactivate() {
    Tenant created = ((Result.Success<Tenant, TenantError>) service.create("Acme")).value();
    Result<Tenant, TenantError> r = service.deactivate(created.id());
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(service.findById(created.id()).orElseThrow().isDeactivated()).isTrue();
  }

  @Test
  @DisplayName("deactivate returns Failure(NotFound) for unknown id")
  void shouldReturnNotFoundOnDeactivateUnknown() {
    Result<Tenant, TenantError> r = service.deactivate(new TenantId(UUID.randomUUID()));
    assertThat(((Result.Failure<Tenant, TenantError>) r).error())
        .isInstanceOf(TenantError.NotFound.class);
  }

  // ---- fakes ----

  private static final class FakeTenantRepository implements TenantRepository {
    final Map<TenantId, Tenant> byId = new HashMap<>();
    final List<Tenant> ordered = new ArrayList<>();

    @Override
    public Result<Tenant, TenantError> save(Tenant t) {
      byId.put(t.id(), t);
      ordered.add(t);
      return Result.success(t);
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Tenant> findAll() {
      return new ArrayList<>(ordered);
    }

    @Override
    public Result<Tenant, TenantError> deactivate(TenantId id) {
      Tenant existing = byId.get(id);
      if (existing == null) {
        return Result.failure(new TenantError.NotFound(id));
      }
      Tenant updated =
          new Tenant(existing.id(), existing.name(), existing.createdAt(), Optional.of(FIXED_TIME));
      byId.put(id, updated);
      ordered.replaceAll(t -> t.id().equals(id) ? updated : t);
      return Result.success(updated);
    }
  }

  private static final class FakeUuidSupplier implements Supplier<UUID> {
    int counter = 0;

    @Override
    public UUID get() {
      counter++;
      return UUID.fromString(String.format("01902f9f-0000-7000-8000-00000000000%d", counter));
    }
  }
}
```

- [ ] **Step 2: Run to fail** — `./mvnw -B test -Dtest=TenantServiceTest`

- [ ] **Step 3: Create the service**

```java
package co.embracejoy.accounting.keystone.application.tenancy;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Use-case service for tenant CRUD. Called only from platform-admin endpoints; persistence
 * uses the {@code BYPASSRLS}-grant'd {@code DataSource}.
 */
public final class TenantService {

  private final TenantRepository repository;
  private final Clock clock;
  private final Supplier<UUID> uuidSupplier;

  public TenantService(TenantRepository repository, Clock clock, Supplier<UUID> uuidSupplier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
  }

  public Result<Tenant, TenantError> create(String name) {
    if (name == null || name.isBlank()) {
      return Result.failure(new TenantError.InvalidName("name must not be blank"));
    }
    Tenant t = new Tenant(new TenantId(uuidSupplier.get()), name, clock.instant(), Optional.empty());
    return repository.save(t);
  }

  public Optional<Tenant> findById(TenantId id) {
    return repository.findById(id);
  }

  public List<Tenant> findAll() {
    return repository.findAll();
  }

  public Result<Tenant, TenantError> deactivate(TenantId id) {
    return repository.deactivate(id);
  }
}
```

- [ ] **Step 4: Run to pass** — expect 6 tests pass.

- [ ] **Step 5: Commit** — body: "create / findById / findAll / deactivate. UUID generation and clock injected for test determinism. Tests use a hand-rolled fake repo + a deterministic UUID supplier."

---

## Task A12: `UserRoleService` (with can't-orphan-self enforcement)

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/security/UserRoleService.java`
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/security/UserRoleServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.application.security;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserRoleService")
class UserRoleServiceTest {

  private static final Instant T0 = Instant.parse("2026-05-13T10:00:00Z");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final String ALICE = "auth0|alice";
  private static final String BOB = "auth0|bob";

  private FakeTenantUserRoleRepository repo;
  private UserRoleService service;

  @BeforeEach
  void setup() {
    repo = new FakeTenantUserRoleRepository();
    service = new UserRoleService(repo, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("grant inserts a new role assignment")
  void shouldGrantNewRole() {
    Result<TenantUserRole, SecurityError> r = service.grant(TENANT, ALICE, Role.BOOKKEEPER, BOB);
    assertThat(r).isInstanceOf(Result.Success.class);
    TenantUserRole row = ((Result.Success<TenantUserRole, SecurityError>) r).value();
    assertThat(row.role()).isEqualTo(Role.BOOKKEEPER);
    assertThat(row.grantedBy()).isEqualTo(BOB);
    assertThat(row.grantedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("grant is idempotent — same role twice returns the same row")
  void shouldBeIdempotent() {
    service.grant(TENANT, ALICE, Role.BOOKKEEPER, BOB);
    Result<TenantUserRole, SecurityError> second =
        service.grant(TENANT, ALICE, Role.BOOKKEEPER, BOB);
    assertThat(second).isInstanceOf(Result.Success.class);
    assertThat(repo.byKey).hasSize(1);
  }

  @Test
  @DisplayName("grant replaces the role on re-grant with a different role")
  void shouldReplaceRoleOnRegrant() {
    service.grant(TENANT, ALICE, Role.BOOKKEEPER, BOB);
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    assertThat(repo.byKey.get(new Key(TENANT, ALICE)).role()).isEqualTo(Role.ADMIN);
  }

  @Test
  @DisplayName("revoke removes the assignment")
  void shouldRevokeRole() {
    service.grant(TENANT, ALICE, Role.BOOKKEEPER, BOB);
    Result<Void, SecurityError> r = service.revoke(TENANT, ALICE, BOB);
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byKey).isEmpty();
  }

  @Test
  @DisplayName("revoke returns RoleNotFound when no assignment exists")
  void shouldReturnRoleNotFoundOnRevokeMissing() {
    Result<Void, SecurityError> r = service.revoke(TENANT, ALICE, BOB);
    assertThat(((Result.Failure<Void, SecurityError>) r).error())
        .isInstanceOf(SecurityError.RoleNotFound.class);
  }

  @Test
  @DisplayName("cannot revoke the only Admin's own Admin role (orphan self)")
  void shouldRejectOrphanSelfOnRevoke() {
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    Result<Void, SecurityError> r = service.revoke(TENANT, ALICE, ALICE);
    assertThat(((Result.Failure<Void, SecurityError>) r).error())
        .isInstanceOf(SecurityError.CannotOrphanSelf.class);
  }

  @Test
  @DisplayName("cannot demote the only Admin to a lesser role (orphan self)")
  void shouldRejectOrphanSelfOnDemote() {
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    Result<TenantUserRole, SecurityError> r =
        service.grant(TENANT, ALICE, Role.BOOKKEEPER, ALICE);
    assertThat(((Result.Failure<TenantUserRole, SecurityError>) r).error())
        .isInstanceOf(SecurityError.CannotOrphanSelf.class);
  }

  @Test
  @DisplayName("can demote yourself when another Admin still exists")
  void shouldAllowSelfDemoteWhenOtherAdminExists() {
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    service.grant(TENANT, BOB, Role.ADMIN, ALICE);
    Result<TenantUserRole, SecurityError> r =
        service.grant(TENANT, ALICE, Role.BOOKKEEPER, ALICE);
    assertThat(r).isInstanceOf(Result.Success.class);
  }

  @Test
  @DisplayName("findByTenant returns all assignments for the tenant")
  void shouldListAssignmentsByTenant() {
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    service.grant(TENANT, BOB, Role.READ_ONLY, ALICE);
    List<TenantUserRole> rows = service.findByTenant(TENANT);
    assertThat(rows).hasSize(2);
  }

  // ---- fake ----

  private record Key(TenantId tenantId, String userSub) {}

  private static final class FakeTenantUserRoleRepository implements TenantUserRoleRepository {
    final Map<Key, TenantUserRole> byKey = new HashMap<>();

    @Override
    public TenantUserRole grant(TenantUserRole assignment) {
      byKey.put(new Key(assignment.tenantId(), assignment.userSub()), assignment);
      return assignment;
    }

    @Override
    public Optional<TenantUserRole> findRole(TenantId tenantId, String userSub) {
      return Optional.ofNullable(byKey.get(new Key(tenantId, userSub)));
    }

    @Override
    public List<TenantUserRole> findByTenant(TenantId tenantId) {
      List<TenantUserRole> out = new ArrayList<>();
      for (Map.Entry<Key, TenantUserRole> e : byKey.entrySet()) {
        if (e.getKey().tenantId().equals(tenantId)) out.add(e.getValue());
      }
      return out;
    }

    @Override
    public boolean revoke(TenantId tenantId, String userSub) {
      return byKey.remove(new Key(tenantId, userSub)) != null;
    }

    @Override
    public long countAdmins(TenantId tenantId) {
      return byKey.values().stream()
          .filter(r -> r.tenantId().equals(tenantId) && r.role() == Role.ADMIN)
          .count();
    }
  }
}
```

- [ ] **Step 2: Run to fail.**

- [ ] **Step 3: Create the service**

```java
package co.embracejoy.accounting.keystone.application.security;

import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.SecurityError;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tenant-scoped user role management. Called only from tenant-admin endpoints. Enforces the
 * "can't orphan self" rule: the lone Admin in a tenant cannot revoke or demote themselves.
 */
public final class UserRoleService {

  private final TenantUserRoleRepository repository;
  private final Clock clock;

  public UserRoleService(TenantUserRoleRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Result<TenantUserRole, SecurityError> grant(
      TenantId tenantId, String userSub, Role newRole, String grantedBy) {
    if (newRole != Role.ADMIN && grantedBy.equals(userSub)) {
      Optional<TenantUserRole> currentlyHeld = repository.findRole(tenantId, userSub);
      if (currentlyHeld.isPresent()
          && currentlyHeld.get().role() == Role.ADMIN
          && repository.countAdmins(tenantId) <= 1) {
        return Result.failure(new SecurityError.CannotOrphanSelf(tenantId, userSub));
      }
    }
    TenantUserRole row =
        new TenantUserRole(tenantId, userSub, newRole, clock.instant(), grantedBy);
    return Result.success(repository.grant(row));
  }

  public List<TenantUserRole> findByTenant(TenantId tenantId) {
    return repository.findByTenant(tenantId);
  }

  public Optional<TenantUserRole> findRole(TenantId tenantId, String userSub) {
    return repository.findRole(tenantId, userSub);
  }

  public Result<Void, SecurityError> revoke(TenantId tenantId, String userSub, String revokedBy) {
    Optional<TenantUserRole> existing = repository.findRole(tenantId, userSub);
    if (existing.isEmpty()) {
      return Result.failure(new SecurityError.RoleNotFound(tenantId, userSub));
    }
    if (existing.get().role() == Role.ADMIN
        && revokedBy.equals(userSub)
        && repository.countAdmins(tenantId) <= 1) {
      return Result.failure(new SecurityError.CannotOrphanSelf(tenantId, userSub));
    }
    repository.revoke(tenantId, userSub);
    return Result.success(null);
  }
}
```

- [ ] **Step 4: Run — expect 9 tests pass.**

- [ ] **Step 5: Commit** — body: "grant (idempotent on same role; replaces on different role), findByTenant, findRole, revoke. Enforces can't-orphan-self both on demote and on revoke. Tests cover: new grant, idempotency, replace-on-different-role, revoke happy path, RoleNotFound, both orphan-self paths, positive case where another Admin exists."

---

## Task A13: Phase A acceptance — verify all green

- [ ] **Step 1: Full local gate**

```
./mvnw -B verify
```

Expected: BUILD SUCCESS. All Phase A tests + existing tests green.

- [ ] **Step 2: Push the branch + open draft PR**

```
git push -u origin 16-slice-5-phase-a
gh pr create --draft --title "Slice 5 Phase A: tenancy + security domain types" --body "..."
```

Phase B continues on the same branch — convert the draft PR to ready when Phase B's changes land, or open Phase B as a stacked PR.


---

# Phase B — Tenant-aware persistence + Postgres RLS

Phase B does the heavy lifting: V6 migration adds `tenant_id` to every business table and creates the new tenancy/security tables; existing domain records gain a `TenantId` field; every existing JPA entity, mapper, and adapter is updated; two `DataSource`s are wired (app + platform/BYPASSRLS); RLS policies + a transaction interceptor enforce isolation at the database level; the centerpiece `RowLevelSecurityIT` verifies both layers.

This phase is intentionally aggressive about scope: persistence + domain changes are inseparable, so they all land together. ~14 tasks, ~15 commits.

**Interim state after Phase B**: API endpoints are still unauthenticated (no JWT required) but every request lands in the default tenant via the temporary `DefaultTenantFilter`. Phase C swaps the default-tenant filter for the JWT-derived one.

---

## Task B1: V6 migration — `V6__tenancy_and_rbac.sql`

**Files:**
- Create: `src/main/resources/db/migration/V6__tenancy_and_rbac.sql`

This is the largest single migration in the project. It runs in one transaction (Flyway default).

- [ ] **Step 1: Generate the default-tenant UUID and record it**

The default-tenant UUID is referenced from both the migration (as a literal) and `application.yaml` (as the config value). Generate one stable UUID v7 once and use it throughout:

```bash
# One-time generation. Pick the value and commit it.
python3 -c "import uuid; print(uuid.uuid7() if hasattr(uuid, 'uuid7') else 'use-an-online-uuid7-generator')"
# Or use any UUID v7 generator. Example value to use throughout the slice:
#   01902f9f-0000-7000-8000-00000000d1f1   (DEFAULT — change to your generated value)
```

Use a single agreed-upon UUID v7 in `V6__tenancy_and_rbac.sql`, `application.yaml`'s `keystone.default-tenant-id`, the `DefaultTenantFilter` (Task B2), and any tests that reference the default tenant. Document it as `DEFAULT_TENANT_UUID` in a constants location (suggest: `src/main/java/.../infrastructure/security/Tenants.java` with `public static final UUID DEFAULT_TENANT_UUID = UUID.fromString("...");`).

- [ ] **Step 2: Write the migration**

Create `src/main/resources/db/migration/V6__tenancy_and_rbac.sql`:

```sql
-- V6: Tenancy + RBAC.
--
-- Adds tenant_id (NOT NULL, FK) to every business table; creates a Default Tenant
-- and backfills existing rows; introduces the tenancy + role tables;
-- enables Postgres Row-Level Security as the database-side backstop.
--
-- See ADR-0016 for the design rationale.

-- ---------------------------------------------------------------------------
-- 1. Tenancy + role tables
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deactivated_at  TIMESTAMPTZ
);

CREATE TABLE tenant_user_roles (
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_sub     VARCHAR(255) NOT NULL,
    role         VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN', 'BOOKKEEPER', 'READ_ONLY')),
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (tenant_id, user_sub)
);
CREATE INDEX idx_tenant_user_roles_user ON tenant_user_roles (user_sub);

CREATE TABLE platform_admins (
    user_sub    VARCHAR(255) PRIMARY KEY,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 2. Insert the Default Tenant
-- ---------------------------------------------------------------------------

INSERT INTO tenants (id, name)
VALUES ('01902f9f-0000-7000-8000-00000000d1f1', 'Default Tenant');

-- ---------------------------------------------------------------------------
-- 3. Add tenant_id to existing business tables (NULLABLE, then backfill, then NOT NULL)
-- ---------------------------------------------------------------------------

ALTER TABLE accounts        ADD COLUMN tenant_id UUID;
ALTER TABLE journal_entries ADD COLUMN tenant_id UUID;
ALTER TABLE postings        ADD COLUMN tenant_id UUID;
ALTER TABLE periods         ADD COLUMN tenant_id UUID;

UPDATE accounts        SET tenant_id = '01902f9f-0000-7000-8000-00000000d1f1';
UPDATE journal_entries SET tenant_id = '01902f9f-0000-7000-8000-00000000d1f1';
UPDATE postings        SET tenant_id = '01902f9f-0000-7000-8000-00000000d1f1';
UPDATE periods         SET tenant_id = '01902f9f-0000-7000-8000-00000000d1f1';

ALTER TABLE accounts        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE journal_entries ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE postings        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE periods         ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE accounts        ADD CONSTRAINT fk_accounts_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE journal_entries ADD CONSTRAINT fk_journal_entries_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE postings        ADD CONSTRAINT fk_postings_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE periods         ADD CONSTRAINT fk_periods_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);

-- ---------------------------------------------------------------------------
-- 4. Composite primary keys (uniqueness is per-tenant)
-- ---------------------------------------------------------------------------

-- Drop the existing single-column PK on accounts.code, recreate as (tenant_id, code).
-- Postings reference (account_code) → must update FK first to drop the old PK.
ALTER TABLE postings DROP CONSTRAINT postings_account_code_fkey;

ALTER TABLE accounts DROP CONSTRAINT accounts_pkey;
ALTER TABLE accounts ADD PRIMARY KEY (tenant_id, code);

ALTER TABLE postings ADD CONSTRAINT postings_account_code_fkey
    FOREIGN KEY (tenant_id, account_code) REFERENCES accounts(tenant_id, code);

ALTER TABLE periods DROP CONSTRAINT periods_pkey;
ALTER TABLE periods ADD PRIMARY KEY (tenant_id, year_month);

-- ---------------------------------------------------------------------------
-- 5. Indexes — tenant_id leads every plan
-- ---------------------------------------------------------------------------

CREATE INDEX idx_accounts_tenant_code        ON accounts        (tenant_id, code);
CREATE INDEX idx_periods_tenant_year_month   ON periods         (tenant_id, year_month);
CREATE INDEX idx_journal_entries_tenant      ON journal_entries (tenant_id, occurred_on);
CREATE INDEX idx_postings_tenant_account     ON postings        (tenant_id, account_code);

-- ---------------------------------------------------------------------------
-- 6. Row-Level Security on tenant-scoped tables
-- ---------------------------------------------------------------------------

ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY accounts_tenant_isolation ON accounts
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY journal_entries_tenant_isolation ON journal_entries
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY postings_tenant_isolation ON postings
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE periods ENABLE ROW LEVEL SECURITY;
CREATE POLICY periods_tenant_isolation ON periods
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

ALTER TABLE tenant_user_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_user_roles_tenant_isolation ON tenant_user_roles
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- tenants and platform_admins are NOT RLS-enabled — they need cross-tenant visibility
-- for the BYPASSRLS-grant'd platform pool (see ADR-0016).
```

- [ ] **Step 3: Validate the SQL by booting the app**

```
docker compose up -d postgres
./mvnw -B -q validate
docker exec keystone-postgres psql -U keystone -d keystone -c "\dt"
```

Expected: tables `tenants`, `tenant_user_roles`, `platform_admins`, `accounts`, `journal_entries`, `postings`, `periods`, `flyway_schema_history` listed. Default tenant exists:

```
docker exec keystone-postgres psql -U keystone -d keystone -c "SELECT id, name FROM tenants;"
```

- [ ] **Step 4: Commit**

```
git add src/main/resources/db/migration/V6__tenancy_and_rbac.sql
git commit -m "feat(persistence): V6 migration — tenants, roles, RLS, default tenant + backfill"
```

(Body should reference ADR-0016 + summarize the migration's six sections.)

---

## Task B2: `TenantContext` bean + `DefaultTenantFilter` (stub) + `RlsTransactionInterceptor`

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/Tenants.java` (constants)
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/TenantContext.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/DefaultTenantFilter.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/RlsTransactionInterceptor.java`

The `DefaultTenantFilter` is Phase B's stub — it populates `TenantContext` with the default tenant on every request. Phase C deletes it and wires `JwtTenantConverter` instead.

- [ ] **Step 1: Create constants**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.UUID;

/** Single source of truth for the default-tenant UUID generated in V6 migration. */
public final class Tenants {

  /** Matches the literal in V6__tenancy_and_rbac.sql and application.yaml. */
  public static final UUID DEFAULT_TENANT_UUID =
      UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1");

  public static final TenantId DEFAULT_TENANT_ID = new TenantId(DEFAULT_TENANT_UUID);

  private Tenants() {}
}
```

- [ ] **Step 2: Create `TenantContext`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped holder for the tenant of the current request.
 *
 * <p>Populated by {@code DefaultTenantFilter} in Phase B (always returns the default tenant)
 * and by {@code JwtTenantConverter} in Phase C (reads from the JWT custom claim).
 */
@Component
@RequestScope
public class TenantContext {

  private TenantId tenantId;

  /** Set the current tenant. Idempotent — overwrites any previous value. */
  public void set(TenantId tenantId) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
  }

  /** Return the current tenant; throws {@link IllegalStateException} if not set. */
  public TenantId require() {
    if (tenantId == null) {
      throw new IllegalStateException("no tenant in current request context");
    }
    return tenantId;
  }

  public Optional<TenantId> current() {
    return Optional.ofNullable(tenantId);
  }
}
```

- [ ] **Step 3: Create `DefaultTenantFilter`** (Phase B stub)

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PHASE B STUB: Populates {@link TenantContext} with the default tenant on every request.
 *
 * <p>This filter is REMOVED in Phase C and replaced by {@code JwtTenantConverter}, which
 * derives the tenant from the JWT custom claim. It exists only so Phase B's persistence
 * changes have a working {@link TenantContext} end-to-end without yet requiring auth.
 */
@Component
@Order(0)
public class DefaultTenantFilter extends OncePerRequestFilter {

  private final TenantContext tenantContext;

  public DefaultTenantFilter(TenantContext tenantContext) {
    this.tenantContext = tenantContext;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    tenantContext.set(Tenants.DEFAULT_TENANT_ID);
    chain.doFilter(req, res);
  }
}
```

- [ ] **Step 4: Create `RlsTransactionInterceptor`**

This intercepts every transaction on the app DataSource and runs `SET LOCAL app.current_tenant = '<uuid>'` so RLS policies can read the GUC. We use a JPA `EntityManagerFactoryInfo`-aware `TransactionalEventListener` or a connection callback. Simplest: a Hibernate `StatementInspector` is too low-level; the cleanest pattern is a custom `TransactionSynchronization`:

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sets the {@code app.current_tenant} Postgres GUC at the start of every transaction on the
 * app {@code DataSource}, so RLS policies can read the GUC and filter rows.
 *
 * <p>Hooks the Spring transaction lifecycle: when a tx is created and the {@link
 * TenantContext} has a value, register a synchronization that runs {@code SET LOCAL
 * app.current_tenant = ?} immediately. {@code SET LOCAL} is auto-reset at COMMIT or ROLLBACK,
 * so connection pool reuse is safe.
 */
@Component
public class RlsTransactionInterceptor {

  @PersistenceContext private EntityManager em;
  @Autowired private TenantContext tenantContext;

  /**
   * Public hook called by repository adapters at the start of any tenant-scoped transaction.
   * The adapters call this from a Spring-managed @Transactional method, so by the time we
   * run, a transaction is active.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void applyToCurrentTransaction() {
    tenantContext
        .current()
        .ifPresent(
            tid -> {
              // SET LOCAL is scoped to the current tx; resets at COMMIT/ROLLBACK.
              em.createNativeQuery("SET LOCAL app.current_tenant = :tid")
                  .setParameter("tid", tid.value().toString())
                  .executeUpdate();
              if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {});
              }
            });
  }
}
```

**Note:** The cleanest pattern is to register `RlsTransactionInterceptor.applyToCurrentTransaction()` as a `@TransactionalEventListener(phase = BEFORE_COMMIT)` or to put it inside an aspect. The reference implementation above puts the call point in the adapter (each adapter calls `interceptor.applyToCurrentTransaction()` as the first statement of any tenant-scoped op). The aspect-based version is cleaner long-term but requires `spring-boot-starter-aop` — defer that to a refactor.

For Phase B, accept the explicit-call pattern in adapters. The verification path is `RowLevelSecurityIT` (Task B12) which exercises both layers.

- [ ] **Step 5: Compile + commit**

```
./mvnw -B -q compile
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/
git commit -m "feat(security): TenantContext + DefaultTenantFilter (Phase B stub) + RlsTransactionInterceptor"
```

Body: "TenantContext is the request-scoped tenant holder. DefaultTenantFilter is the Phase B stub (removed in Phase C). RlsTransactionInterceptor sets app.current_tenant GUC; called explicitly from adapters."

---

## Task B3: `KeystoneSecurityProperties` + `DataSourcesConfig` (two pools)

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/KeystoneSecurityProperties.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/DataSourcesConfig.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Properties record**

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for security-related defaults. Issuer URI / audience / tenant claim are wired
 * in Phase C; the bootstrap-platform-admin-sub is read by PlatformAdminBootstrap (Phase C).
 *
 * <p>Phase B uses only {@code defaultTenantId}. Phase C adds the rest.
 */
@ConfigurationProperties("keystone.security")
public record KeystoneSecurityProperties(
    String issuerUri,
    String audience,
    String tenantClaim,
    String bootstrapPlatformAdminSub) {

  public KeystoneSecurityProperties {
    // Phase B: these can all be null. Phase C adds @PostConstruct validation.
    if (tenantClaim == null || tenantClaim.isBlank()) {
      tenantClaim = "https://keystone.embracejoy.co/tenant_id";
    }
    if (bootstrapPlatformAdminSub == null) {
      bootstrapPlatformAdminSub = "";
    }
  }
}
```

- [ ] **Step 2: Two DataSources config**

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two DataSources:
 *
 * <ul>
 *   <li>{@code appDataSource} (@Primary) — connected as the unprivileged {@code keystone} user.
 *       Subject to Postgres RLS. Used by JPA repositories that operate within a tenant.
 *   <li>{@code platformDataSource} — connected as {@code keystone_platform}, who has
 *       {@code BYPASSRLS}. Used by repositories serving platform-admin endpoints
 *       (TenantRepository, PlatformAdminRepository) and by the Flyway migration runner.
 * </ul>
 *
 * <p>See {@code docs/operations/setup-platform-role.sql} for the one-time Postgres role setup.
 */
@Configuration
public class DataSourcesConfig {

  @Bean
  @Primary
  @ConfigurationProperties("keystone.datasource.app")
  public DataSource appDataSource() {
    return DataSourceBuilder.create().build();
  }

  @Bean
  @ConfigurationProperties("keystone.datasource.platform")
  public DataSource platformDataSource() {
    return DataSourceBuilder.create().build();
  }
}
```

- [ ] **Step 3: Update `application.yaml`**

```yaml
keystone:
  base-currency: USD
  default-tenant-id: "01902f9f-0000-7000-8000-00000000d1f1"
  security:
    tenant-claim: "https://keystone.embracejoy.co/tenant_id"
    # issuer-uri, audience, bootstrap-platform-admin-sub: filled in Phase C
  datasource:
    app:
      url: ${DATABASE_URL:jdbc:postgresql://localhost:5434/keystone}
      username: ${DATABASE_USER:keystone}
      password: ${DATABASE_PASSWORD:keystone}
      driver-class-name: org.postgresql.Driver
    platform:
      url: ${DATABASE_URL:jdbc:postgresql://localhost:5434/keystone}
      username: ${KEYSTONE_PLATFORM_DB_USER:keystone_platform}
      password: ${KEYSTONE_PLATFORM_DB_PASSWORD:keystone_platform}
      driver-class-name: org.postgresql.Driver
      hikari:
        maximum-pool-size: 2
```

The platform pool is small (max 2) — admin operations are rare.

**Important:** the existing `spring.datasource.*` keys become unused. Remove them or migrate to the new `keystone.datasource.app.*` shape. JPA's `EntityManagerFactory` auto-config resolves to the `@Primary` bean automatically.

- [ ] **Step 4: Update `docker-compose.yml`** to provision the platform role

Add an init script:

```yaml
# docker-compose.yml — add to the postgres service:
postgres:
  image: postgres:16
  container_name: keystone-postgres
  environment:
    POSTGRES_USER: keystone
    POSTGRES_PASSWORD: keystone
    POSTGRES_DB: keystone
  ports:
    - "5434:5432"
  volumes:
    - postgres-data:/var/lib/postgresql/data
    - ./docs/operations/setup-platform-role.sql:/docker-entrypoint-initdb.d/01-setup-platform-role.sql:ro
  healthcheck:
    test: ["CMD", "pg_isready", "-U", "keystone", "-d", "keystone"]
```

`docker-entrypoint-initdb.d/*.sql` runs once at first container init. For a fresh dev environment, this provisions the role automatically. For an existing dev environment, run `setup-platform-role.sql` manually once.

- [ ] **Step 5: Create `docs/operations/setup-platform-role.sql`**

```sql
-- One-time Postgres role provisioning for the keystone platform pool.
-- Run as a Postgres superuser. Safe to re-run (idempotent guards).

DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'keystone_platform') THEN
      CREATE USER keystone_platform WITH PASSWORD 'keystone_platform';
   END IF;
END
$$;

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO keystone_platform;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO keystone_platform;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO keystone_platform;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO keystone_platform;

ALTER USER keystone_platform BYPASSRLS;
```

- [ ] **Step 6: Boot to validate**

```
docker compose down -v && docker compose up -d postgres
./mvnw -B -q compile
docker exec keystone-postgres psql -U keystone -d keystone -c "\du keystone_platform"
```

Expected: `keystone_platform` listed with `Bypass RLS` attribute.

- [ ] **Step 7: Commit**

```
git add src/main/java/co/embracejoy/accounting/keystone/infrastructure/config/ \
        src/main/resources/application.yaml \
        docker-compose.yml \
        docs/operations/setup-platform-role.sql
git commit -m "feat(config): two DataSources (app + platform/BYPASSRLS) + KeystoneSecurityProperties"
```

---

## Task B4: `Account` aggregate becomes tenant-aware (the model)

This is the worked example for the aggregate transformation. Period (Task B5) and JournalEntry (Task B6) follow the same pattern with their own specifics.

**Files:**
- Modify: `src/main/java/.../domain/account/Account.java`
- Modify: `src/test/java/.../domain/account/AccountTest.java`
- Modify: `src/main/java/.../infrastructure/persistence/account/AccountEntity.java`
- Modify: `src/main/java/.../infrastructure/persistence/account/AccountEntityMapper.java`
- Modify: `src/main/java/.../infrastructure/persistence/account/JpaAccountRepository.java`
- Modify: `src/main/java/.../infrastructure/persistence/account/AccountRepositoryAdapter.java`
- Modify: `src/test/java/.../infrastructure/persistence/account/AccountRepositoryAdapterIT.java`
- Modify: `src/main/java/.../application/account/AccountService.java`
- Modify: `src/test/java/.../application/account/AccountServiceTest.java`
- Modify: `src/main/java/.../infrastructure/web/account/AccountController.java`
- Modify: `src/test/java/.../infrastructure/web/account/AccountControllerTest.java`

- [ ] **Step 1: Update `AccountTest` first (TDD red)**

Update every `new Account(...)` call site in `AccountTest` to pass `TenantId` as the **first** field. Add a constant:

```java
private static final TenantId TENANT =
    new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));
```

And add a new test for the null-tenant invariant:

```java
@Test
@DisplayName("rejects null tenantId")
void shouldThrowWhenTenantIdIsNull() {
  assertThrows(
      NullPointerException.class,
      () -> new Account(null, CASH, "Cash", AccountType.ASSET, USD, Optional.empty(), AccountStatus.ACTIVE));
}
```

Update every existing test to include the `TENANT` parameter as the first arg.

- [ ] **Step 2: Run to fail** — `./mvnw -B test -Dtest=AccountTest`

- [ ] **Step 3: Update `Account.java`**

Add `TenantId tenantId` as the first record component:

```java
package co.embracejoy.accounting.keystone.domain.account;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * An account in the chart of accounts, scoped to a {@link TenantId}.
 *
 * <p>{@link AccountCode} is the natural primary key WITHIN a tenant; the same code can exist
 * in two different tenants (composite PK in storage). Optional {@link #parentCode()} forms a
 * hierarchy. Leaf-only posting is enforced at {@code JournalEntry.of(...)} time, not here.
 */
public record Account(
    TenantId tenantId,
    AccountCode code,
    String name,
    AccountType type,
    Currency currency,
    Optional<AccountCode> parentCode,
    AccountStatus status) {

  public Account {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(parentCode, "parentCode");
    Objects.requireNonNull(status, "status");
    if (parentCode.isPresent() && parentCode.get().equals(code)) {
      throw new IllegalArgumentException("account cannot be its own parent");
    }
  }

  public NormalSide normalSide() {
    return type.normalSide();
  }

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }
}
```

- [ ] **Step 4: Update `AccountEntity`**

Add a `tenant_id UUID NOT NULL` column. Composite PK is `(tenant_id, code)`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@IdClass(AccountEntity.AccountKey.class)
public class AccountEntity {

  @Id
  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Id
  @Column(name = "code", length = 64, nullable = false)
  private String code;

  @Column(name = "name", length = 200, nullable = false)
  private String name;

  @Column(name = "type", length = 32, nullable = false)
  private String type;

  @Column(name = "currency", length = 3, nullable = false)
  private String currency;

  @Column(name = "parent_code", length = 64)
  private String parentCode;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected AccountEntity() {}

  public AccountEntity(
      UUID tenantId, String code, String name, String type, String currency,
      String parentCode, boolean active) {
    this.tenantId = tenantId;
    this.code = code;
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.parentCode = parentCode;
    this.active = active;
  }

  // Getters + setters as before, plus getTenantId / setTenantId

  public UUID getTenantId() { return tenantId; }
  public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
  public String getCode() { return code; }
  // ... (rest of getters/setters from existing file)

  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }

  /** Composite PK class for @IdClass. */
  public static class AccountKey implements Serializable {
    private UUID tenantId;
    private String code;

    public AccountKey() {}
    public AccountKey(UUID tenantId, String code) {
      this.tenantId = tenantId;
      this.code = code;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AccountKey k)) return false;
      return Objects.equals(tenantId, k.tenantId) && Objects.equals(code, k.code);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, code); }
  }
}
```

- [ ] **Step 5: Update `AccountEntityMapper`**

Both directions translate `tenantId`:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Currency;
import java.util.Optional;

final class AccountEntityMapper {

  private AccountEntityMapper() {}

  static AccountEntity toEntity(Account a) {
    return new AccountEntity(
        a.tenantId().value(),
        a.code().value(),
        a.name(),
        a.type().name(),
        a.currency().getCurrencyCode(),
        a.parentCode().map(AccountCode::value).orElse(null),
        a.isActive());
  }

  static Account toDomain(AccountEntity e) {
    return new Account(
        new TenantId(e.getTenantId()),
        new AccountCode(e.getCode()),
        e.getName(),
        AccountType.valueOf(e.getType()),
        Currency.getInstance(e.getCurrency()),
        Optional.ofNullable(e.getParentCode()).map(AccountCode::new),
        e.isActive() ? AccountStatus.ACTIVE : AccountStatus.INACTIVE);
  }
}
```

- [ ] **Step 6: Update `JpaAccountRepository`**

Spring Data derived queries take `tenantId` as a parameter:

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JpaAccountRepository
    extends JpaRepository<AccountEntity, AccountEntity.AccountKey> {

  Optional<AccountEntity> findByTenantIdAndCode(UUID tenantId, String code);

  boolean existsByTenantIdAndCode(UUID tenantId, String code);

  List<AccountEntity> findAllByTenantId(UUID tenantId);

  List<AccountEntity> findAllByTenantIdAndCodeIn(UUID tenantId, List<String> codes);

  boolean existsByTenantIdAndParentCode(UUID tenantId, String parentCode);

  @Modifying
  @Query(
      "UPDATE AccountEntity a SET a.code = :newCode "
          + "WHERE a.tenantId = :tenantId AND a.code = :existing")
  int renameCode(
      @Param("tenantId") UUID tenantId,
      @Param("existing") String existing,
      @Param("newCode") String newCode);
}
```

- [ ] **Step 7: Update `AccountRepositoryAdapter`**

Inject `TenantContext`. Every method reads it. The save path also validates that the domain object's tenant matches.

```java
package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccountRepositoryAdapter implements AccountRepository {

  private final JpaAccountRepository jpa;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public AccountRepositoryAdapter(
      JpaAccountRepository jpa,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jpa = jpa;
    this.tenantContext = tenantContext;
    this.rlsInterceptor = rlsInterceptor;
  }

  @Override
  public Result<Account, AccountError> save(Account account) {
    var tenantId = tenantContext.require();
    if (!tenantId.equals(account.tenantId())) {
      throw new IllegalStateException(
          "tenant mismatch — account is " + account.tenantId() + ", context is " + tenantId);
    }
    rlsInterceptor.applyToCurrentTransaction();
    UUID tid = tenantId.value();
    if (jpa.existsByTenantIdAndCode(tid, account.code().value())) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
    try {
      AccountEntity saved = jpa.save(AccountEntityMapper.toEntity(account));
      return Result.success(AccountEntityMapper.toDomain(saved));
    } catch (DataIntegrityViolationException ex) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
  }

  @Override
  public Result<Account, AccountError> update(Account account) {
    var tenantId = tenantContext.require();
    if (!tenantId.equals(account.tenantId())) {
      throw new IllegalStateException("tenant mismatch on update");
    }
    rlsInterceptor.applyToCurrentTransaction();
    UUID tid = tenantId.value();
    if (!jpa.existsByTenantIdAndCode(tid, account.code().value())) {
      return Result.failure(new AccountError.NotFound(account.code()));
    }
    AccountEntity entity =
        jpa.findByTenantIdAndCode(tid, account.code().value()).orElseThrow();
    entity.setName(account.name());
    entity.setParentCode(account.parentCode().map(AccountCode::value).orElse(null));
    entity.setActive(account.isActive());
    return Result.success(AccountEntityMapper.toDomain(jpa.save(entity)));
  }

  @Override
  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    var tenantId = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    UUID tid = tenantId.value();
    if (jpa.existsByTenantIdAndCode(tid, newCode.value())) {
      return Result.failure(new AccountError.CodeInUseByPosting(newCode));
    }
    if (!jpa.existsByTenantIdAndCode(tid, existing.value())) {
      return Result.failure(new AccountError.NotFound(existing));
    }
    jpa.renameCode(tid, existing.value(), newCode.value());
    return Result.success(
        AccountEntityMapper.toDomain(jpa.findByTenantIdAndCode(tid, newCode.value()).orElseThrow()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> findByCode(AccountCode code) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findByTenantIdAndCode(tenantContext.require().value(), code.value())
        .map(AccountEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Account> findAll() {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findAllByTenantId(tenantContext.require().value()).stream()
        .map(AccountEntityMapper::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
    rlsInterceptor.applyToCurrentTransaction();
    UUID tid = tenantContext.require().value();
    List<String> ids = codes.stream().map(AccountCode::value).toList();
    Map<AccountCode, Account> out = new LinkedHashMap<>();
    for (AccountEntity e : jpa.findAllByTenantIdAndCodeIn(tid, ids)) {
      Account a = AccountEntityMapper.toDomain(e);
      out.put(a.code(), a);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasChildren(AccountCode code) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.existsByTenantIdAndParentCode(tenantContext.require().value(), code.value());
  }
}
```

- [ ] **Step 8: Update `AccountService`**

The service constructs `Account` records — needs to stamp the tenant from `TenantContext`. Inject the context:

```java
package co.embracejoy.accounting.keystone.application.account;

// ... existing imports
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;

public final class AccountService {

  private final AccountRepository repository;
  private final TenantContext tenantContext;

  public AccountService(AccountRepository repository, TenantContext tenantContext) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
  }

  public Result<Account, AccountError> create(
      AccountCode code, String name, AccountType type, Currency currency,
      Optional<AccountCode> parentCode) {
    if (parentCode.isPresent() && repository.findByCode(parentCode.get()).isEmpty()) {
      return Result.failure(new AccountError.ParentNotFound(parentCode.get()));
    }
    return repository.save(
        new Account(
            tenantContext.require(), code, name, type, currency, parentCode, AccountStatus.ACTIVE));
  }

  // setParent, deactivate/reactivate similarly: read TenantContext to construct the new Account.
  // (The full method bodies follow the existing pattern; the only change per method is
  // adding tenantContext.require() as the first arg to new Account(...).)
}
```

- [ ] **Step 9: Update `AccountController`**

Inject `TenantContext` and pass it through. Actually — the controller doesn't need it directly; it calls `AccountService` which already has it. But `AccountResponse.of(...)` may want to include `tenantId` in the response — typically NO, the tenant is implicit from the bearer JWT. Leave the response shape as-is.

The controller may need NO changes if all tenant-handling is inside the service. Confirm by running the controller tests after the service change.

- [ ] **Step 10: Update `AccountServiceTest`**

Inject a fake TenantContext (or use a test-config bean). Every test seeds the context with `Tenants.DEFAULT_TENANT_ID`. The fake repo's `findByCodeIn` etc. return tenant-stamped accounts.

- [ ] **Step 11: Update `AccountRepositoryAdapterIT`**

Set up the tenant context per test (use `@BeforeEach` to call `tenantContext.set(Tenants.DEFAULT_TENANT_ID)`). The IT needs to seed tenants before creating accounts, since FK constraints kick in.

- [ ] **Step 12: Update `AccountControllerTest`**

`@WebMvcTest` doesn't include `TenantContext` automatically. Use `@MockitoBean` on `TenantContext` and stub `require()` to return the default tenant.

- [ ] **Step 13: Run full verify**

```
./mvnw -B verify
```

Expect: BUILD SUCCESS. All Account-related tests pass against the new tenant-aware shape.

- [ ] **Step 14: Apply Spotless and commit**

```
./mvnw -B spotless:apply -q
git add src/main/java/co/embracejoy/accounting/keystone/domain/account/Account.java \
        src/main/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/ \
        src/main/java/co/embracejoy/accounting/keystone/application/account/AccountService.java \
        src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountTest.java \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/persistence/account/ \
        src/test/java/co/embracejoy/accounting/keystone/application/account/ \
        src/test/java/co/embracejoy/accounting/keystone/infrastructure/web/account/
git commit -m "feat(account): Account aggregate becomes tenant-aware"
```

Body should walk through: TenantId added as first record component; AccountEntity has composite PK (tenant_id, code) via @IdClass; mapper translates both directions; JpaAccountRepository uses derived findByTenantIdAnd* queries; AccountRepositoryAdapter injects TenantContext and validates tenant match on save/update; AccountService stamps tenantId from context; tests updated.

---

## Task B5: `Period` aggregate becomes tenant-aware

Apply the same pattern as Task B4 to `Period`. Specific deltas:

**Period record** gains `TenantId tenantId` as first component:

```java
public record Period(
    TenantId tenantId,
    YearMonth yearMonth,
    PeriodStatus status,
    Optional<PeriodCloseInfo> closedInfo,
    Optional<Instant> reopenedAt) {
  // compact constructor adds Objects.requireNonNull(tenantId, "tenantId");
  // rest unchanged
}
```

**PeriodEntity** gains `tenant_id UUID NOT NULL` column with composite PK `(tenant_id, year_month)` via `@IdClass`.

**PeriodEntityMapper** translates `tenantId` in both directions.

**JpaPeriodRepository** uses `findByTenantIdAndYearMonth`, `existsByTenantIdAndYearMonth`, `findAllByTenantIdOrderByYearMonth`.

**PeriodRepositoryAdapter** injects `TenantContext` + `RlsTransactionInterceptor`; reads tenant on every method; validates match on save/update.

**PeriodService** injects `TenantContext`; stamps tenantId on Period construction.

**PeriodController** — likely no changes (service does the work).

**Test updates:** `PeriodTest`, `PeriodServiceTest`, `PeriodRepositoryAdapterIT`, `PeriodControllerTest` — all pass `Tenants.DEFAULT_TENANT_ID` and stub the context.

Run: `./mvnw -B verify`. Commit with body explaining the same pattern applied to Period.

---

## Task B6: `JournalEntry` aggregate becomes tenant-aware

Apply the same pattern to `JournalEntry`. Specific deltas:

**JournalEntry record** gains `TenantId tenantId` as first component:

```java
public record JournalEntry(
    TenantId tenantId,
    LocalDate occurredOn,
    String description,
    List<Posting> postings) {
  public JournalEntry {
    Objects.requireNonNull(tenantId, "tenantId");
    // ... existing checks
  }
}
```

`Posting` does NOT need a `tenantId` field — it inherits from the parent `JournalEntry`. The `PostingEntity` JPA entity DOES carry a `tenant_id` column (the migration enforces it), populated from the parent at mapping time.

**JournalEntryEntity** gains `tenant_id UUID NOT NULL`.

**PostingEntity** gains `tenant_id UUID NOT NULL` (always copied from the parent).

**JournalEntryEntityMapper** copies `tenantId` to both the entry entity and every posting entity.

**JpaJournalEntryRepository** uses derived queries (`findAllByTenantId`, `findFirstByTenantIdAndId`, etc.).

**JournalEntryRepositoryAdapter** injects `TenantContext` + `RlsTransactionInterceptor`; validates tenant match on save.

**`JournalValidationContext`** — the existing record. It needs to know the tenant so account lookups in the validation pass are tenant-scoped. Add `TenantId tenantId` as the first field. Update its compact constructor.

**`PostJournalEntryService`** — already has `Currency baseCurrency`, `JournalEntryRepository`, `AccountRepository`, `PeriodService`. Add `TenantContext`. Constructs `JournalEntry` with `tenantContext.require()`. Builds `JournalValidationContext` with the same tenant.

**`JournalEntryController`** — service does the work; controller-side change is to make sure controller tests stub the tenant context.

**Test updates:** `JournalEntryTest`, `JournalValidationContextTest`, `PostJournalEntryServiceTest`, `JpaJournalEntryRepositoryIT`, `JournalEntryControllerTest`.

Run `./mvnw -B verify`. Commit.

---

## Task B7: `TrialBalanceJdbcReadModel` adds tenant filter

**Files:**
- Modify: `src/main/java/.../infrastructure/persistence/reports/TrialBalanceJdbcReadModel.java`
- Modify: `src/test/java/.../infrastructure/persistence/reports/TrialBalanceJdbcReadModelIT.java`
- Modify: `src/main/java/.../application/reports/TrialBalanceService.java`
- Modify: `src/test/java/.../application/reports/TrialBalanceServiceTest.java`
- Modify: `src/main/java/.../infrastructure/web/reports/TrialBalanceController.java`

- [ ] **Step 1: Update SQL with `:tenantId` named param**

```java
private static final String SQL =
    """
    SELECT p.account_code,
           p.currency,
           SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units  ELSE 0 END) AS debits,
           SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units  ELSE 0 END) AS credits,
           SUM(CASE WHEN p.side = 'DEBIT'  THEN p.base_minor_units    ELSE 0 END) AS base_debits,
           SUM(CASE WHEN p.side = 'CREDIT' THEN p.base_minor_units    ELSE 0 END) AS base_credits
    FROM   postings p
    JOIN   journal_entries je ON je.id = p.journal_entry_id
    WHERE  p.tenant_id = :tenantId
      AND  je.occurred_on <= :asOf
    GROUP  BY p.account_code, p.currency
    HAVING :includeZero OR (SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units ELSE 0 END)
                          - SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units ELSE 0 END)) <> 0
    ORDER  BY p.account_code, p.currency
    """;
```

- [ ] **Step 2: Inject `TenantContext` and pass `:tenantId`**

```java
private final JdbcClient jdbc;
private final TenantContext tenantContext;
private final RlsTransactionInterceptor rlsInterceptor;

public TrialBalanceJdbcReadModel(
    JdbcClient jdbc, TenantContext tenantContext, RlsTransactionInterceptor rlsInterceptor) {
  this.jdbc = jdbc;
  this.tenantContext = tenantContext;
  this.rlsInterceptor = rlsInterceptor;
}

@Override
public List<TrialBalanceRow> fetch(LocalDate asOf, boolean includeZero) {
  rlsInterceptor.applyToCurrentTransaction();
  return jdbc.sql(SQL)
      .param("tenantId", tenantContext.require().value())
      .param("asOf", asOf)
      .param("includeZero", includeZero)
      .query(MAPPER)
      .list();
}
```

- [ ] **Step 3: Update `TrialBalanceJdbcReadModelIT`** — every test seeds the tenant context to a known UUID before calling `fetch`. The existing 5 tests pass with the addition; consider adding `shouldNotSeeOtherTenantsRows` (seeds entries for two tenants, fetches with each context, asserts only your own).

- [ ] **Step 4: `TrialBalanceService` and `TrialBalanceController`** — already pass-through; no changes unless tests demand.

- [ ] **Step 5: Verify + commit**

---

## Task B8: New JPA repos for `Tenant` (uses platform DataSource)

**Files:**
- Create: `src/main/java/.../infrastructure/persistence/tenancy/TenantEntity.java`
- Create: `src/main/java/.../infrastructure/persistence/tenancy/TenantEntityMapper.java`
- Create: `src/main/java/.../infrastructure/persistence/tenancy/JpaTenantRepository.java`
- Create: `src/main/java/.../infrastructure/persistence/tenancy/TenantRepositoryAdapter.java`
- Create: `src/test/java/.../infrastructure/persistence/tenancy/JpaTenantRepositoryIT.java`

The `tenants` table is NOT RLS-protected, but only the `platformDataSource` should write to it (BYPASSRLS isn't strictly needed since RLS isn't on the table — but we keep the convention so platform-admin operations are clearly demarcated).

For Phase B we wire it to the `@Primary` (app) DataSource — Phase D uses the platform DataSource. The test verifies basic CRUD against an integration Postgres.

**TenantEntity:** standard JPA `@Entity` with `id`, `name`, `created_at`, `deactivated_at` columns.

**TenantEntityMapper:** translates between `Tenant` record and `TenantEntity`.

**JpaTenantRepository:** Spring Data `JpaRepository<TenantEntity, UUID>` with `findAllByOrderByCreatedAtAsc()`.

**TenantRepositoryAdapter:** `@Repository @Transactional` implementing `TenantRepository` from the domain. Wraps the JPA repo. `deactivate(id)` reads the entity, sets `deactivatedAt = Instant.now()`, saves.

**JpaTenantRepositoryIT:** Testcontainers-based; seeds + reads + deactivates a tenant; verifies soft delete preserves the row.

Each step follows the worked example shape from Task B4. Commit.

---

## Task B9: New JPA repos for `TenantUserRole`

**Files:**
- Create: `TenantUserRoleEntity` with `@IdClass` for composite PK `(tenant_id, user_sub)`.
- Create: `TenantUserRoleEntityMapper`.
- Create: `JpaTenantUserRoleRepository` with: `findByTenantIdAndUserSub(UUID, String)`, `findAllByTenantIdOrderByGrantedAtAsc(UUID)`, `existsByTenantIdAndUserSub(UUID, String)`, `countByTenantIdAndRole(UUID, String)`, `deleteByTenantIdAndUserSub(UUID, String)`.
- Create: `TenantUserRoleRepositoryAdapter` implementing the domain port. Note: this table IS RLS-protected (created in Task B1 SQL), so the adapter must call `RlsTransactionInterceptor.applyToCurrentTransaction()` and use the app DataSource — except `countAdmins` which the platform pool may need. Decision: `tenant_user_roles` reads happen through the app pool (the requesting user is operating within their own tenant), and platform admins don't see other tenants' role rows directly.
- Create: `JpaTenantUserRoleRepositoryIT` covering: grant/upsert, findRole, findByTenant, revoke, countAdmins.

Commit.

---

## Task B10: New JPA repos for `PlatformAdmin`

**Files:**
- Create: `PlatformAdminEntity` (single-column PK `user_sub`).
- Create: `JpaPlatformAdminRepository` (`existsByUserSub`, `findAllByOrderByGrantedAtAsc`).
- Create: `PlatformAdminRepositoryAdapter` (uses platform DataSource — see Phase D for the actual binding; for Phase B, app DataSource works because `platform_admins` is not RLS-enabled).
- Create: `JpaPlatformAdminRepositoryIT`.

Commit.

---

## Task B11: `RowLevelSecurityIT` — the centerpiece

**Files:**
- Create: `src/test/java/.../infrastructure/security/RowLevelSecurityIT.java`

Tests that BOTH layers (app filter + Postgres RLS) work, both together and individually.

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@DisplayName("RowLevelSecurity (integration)")
class RowLevelSecurityIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired JdbcClient appJdbc; // bound to the app DataSource (no BYPASSRLS)

  private static final UUID TENANT_A = UUID.fromString("01902f9f-0000-7000-8000-aaaaaaaaaaaa");
  private static final UUID TENANT_B = UUID.fromString("01902f9f-0000-7000-8000-bbbbbbbbbbbb");

  @BeforeEach
  void seedTwoTenants() {
    // Run with a temporary BYPASSRLS context (no GUC set) to seed cross-tenant.
    appJdbc.sql("DELETE FROM journal_entries").update();
    appJdbc.sql("DELETE FROM accounts WHERE code LIKE 'RLS%'").update();
    appJdbc.sql("DELETE FROM tenants WHERE id IN (?, ?)").param(TENANT_A).param(TENANT_B).update();
    appJdbc.sql("INSERT INTO tenants (id, name) VALUES (?, ?)")
        .param(TENANT_A).param("Tenant A").update();
    appJdbc.sql("INSERT INTO tenants (id, name) VALUES (?, ?)")
        .param(TENANT_B).param("Tenant B").update();
    setTenant(TENANT_A);
    insertAccount(TENANT_A, "RLS-A1", "A's first");
    setTenant(TENANT_B);
    insertAccount(TENANT_B, "RLS-B1", "B's first");
  }

  @Test
  @DisplayName("with tenant A in GUC, SELECT returns only A's rows")
  void shouldSeeOnlyOwnRowsWhenTenantSet() {
    setTenant(TENANT_A);
    List<Map<String, Object>> rows =
        appJdbc.sql("SELECT code FROM accounts WHERE code LIKE 'RLS%'").query().listOfRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("code")).isEqualTo("RLS-A1");
  }

  @Test
  @DisplayName("switching the GUC switches the visible row set")
  void shouldSwitchVisibilityOnGucChange() {
    setTenant(TENANT_A);
    long aCount = appJdbc.sql("SELECT count(*) FROM accounts WHERE code LIKE 'RLS%'")
        .query(Long.class).single();
    setTenant(TENANT_B);
    long bCount = appJdbc.sql("SELECT count(*) FROM accounts WHERE code LIKE 'RLS%'")
        .query(Long.class).single();
    assertThat(aCount).isEqualTo(1);
    assertThat(bCount).isEqualTo(1);
  }

  @Test
  @DisplayName("unset GUC → zero rows visible (RLS USING clause returns false on NULL)")
  void shouldReturnZeroRowsWhenGucUnset() {
    appJdbc.sql("RESET app.current_tenant").update();
    long count =
        appJdbc.sql("SELECT count(*) FROM accounts WHERE code LIKE 'RLS%'")
            .query(Long.class).single();
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("bogus GUC value → zero rows visible")
  void shouldReturnZeroRowsWhenGucIsUnknownTenant() {
    setTenant(UUID.fromString("01902f9f-0000-7000-8000-deadbeefdead"));
    long count =
        appJdbc.sql("SELECT count(*) FROM accounts WHERE code LIKE 'RLS%'")
            .query(Long.class).single();
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("WITH CHECK rejects an INSERT with a wrong tenant_id for the current GUC")
  void shouldRejectMisroutedInsert() {
    setTenant(TENANT_A);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertAccount(TENANT_B, "RLS-A2", "Misrouted"));
  }

  // ---- helpers ----

  private void setTenant(UUID tenantId) {
    appJdbc.sql("SET LOCAL app.current_tenant = :tid")
        .param("tid", tenantId.toString())
        .update();
  }

  private void insertAccount(UUID tenantId, String code, String name) {
    appJdbc.sql("INSERT INTO accounts (tenant_id, code, name, type, currency, active) "
                + "VALUES (?, ?, ?, 'ASSET', 'USD', true)")
        .param(tenantId).param(code).param(name)
        .update();
  }
}
```

**Note:** The `SET LOCAL` requires being inside a transaction, which `@SpringBootTest` doesn't open by default. Either annotate the class with `@Transactional` (rolls back per test, won't survive across) or use `JdbcClient`'s native transaction-affinity — see existing IT patterns in the repo.

If `SET LOCAL` doesn't take effect outside a tx, swap to a `@Transactional` class-level annotation OR seed via `appJdbc.sql("BEGIN").update()` / `COMMIT`. The simpler fix: annotate the class with `@Transactional` from `org.springframework.transaction.annotation` and rely on Spring's default propagation.

Run: `./mvnw -B test -Dtest=RowLevelSecurityIT`. Commit.

---

## Task B12: Update `ApplicationSmokeIT` for tenant stamping

The smoke IT currently posts entries against the seeded accounts (1000, 1100, 3000, 4000). After Phase B, those accounts are owned by the default tenant. The smoke filter (`DefaultTenantFilter`) populates the context to `Tenants.DEFAULT_TENANT_ID` for every request, so existing smoke tests should pass with one change: the response shouldn't carry tenantId publicly (we don't want it on the wire), but the database row should have it.

Add one new smoke assertion: after posting an entry, verify (via `JdbcClient` directly) that the entry's row has `tenant_id = <default tenant uuid>`.

Run `./mvnw -B verify`. Commit.

---

## Task B13: Phase B acceptance

```
./mvnw -B clean verify -Pmutation,openapi-gate -Dopenapi.diff.skip=true
```

Expect: BUILD SUCCESS. JaCoCo ≥ 85%, PIT ≥ 60%.

Push branch, open PR with title `Slice 5 Phase B: tenant-aware persistence + RLS` and description noting:

> **Interim insecure state**: API endpoints remain unauthenticated in this PR. Every request lands in the default tenant via the `DefaultTenantFilter` stub. Phase C wires OAuth2 and removes the stub. Do not deploy main between Phase B merging and Phase C merging if this matters for your environment.


---

# Phase C — OAuth2 resource server + RBAC enforcement

Phase C adds the security layer: Spring Security with `oauth2-resource-server`, the `JwtTenantConverter` that derives `TenantId` and authorities from the JWT, the `SecurityFilterChain` for the API, `@PreAuthorize` annotations on every existing controller per the permission matrix, and the `SecurityExceptionHandler` for ProblemDetail mapping.

The Phase B `DefaultTenantFilter` is **deleted** — its job is now done by `JwtTenantConverter`.

`PlatformAdminBootstrap` runs at startup to seed the platform admin from the env var.

After Phase C: API requires a JWT for every non-public endpoint; tenant + role come from the token. ~10 tasks.

---

## Task C1: Add Spring Security + OAuth2 dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the two starters**

Add to the `<dependencies>` section:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

For test JWT minting, add Nimbus JOSE+JWT (transitively pulled by oauth2-resource-server already, but make it explicit for tests):

```xml
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Run `./mvnw -B -q compile`** — Spring Security autoconfiguration locks down all endpoints with HTTP Basic by default, breaking every existing controller test. That's the expected starting state for Phase C — tests will be fixed by Tasks C5 onward.

- [ ] **Step 3: Commit dependency-only change**

```
git add pom.xml
git commit -m "build(deps): add Spring Security + OAuth2 resource server starters"
```

---

## Task C2: `JwtTenantConverter`

**Files:**
- Create: `src/main/java/.../infrastructure/security/JwtTenantConverter.java`
- Create: `src/test/java/.../infrastructure/security/JwtTenantConverterTest.java`

- [ ] **Step 1: Write the failing test**

Cover: valid JWT with tenant claim → authorities include `ROLE_<role>` and TenantContext populated; valid JWT with bogus tenant → `InvalidBearerTokenException`; valid JWT, no claim, sub is platform admin → `ROLE_PLATFORM_ADMIN` authority but no TenantContext set; valid JWT, no claim, no platform admin → empty authorities (controllers will 403 via @PreAuthorize).

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

@DisplayName("JwtTenantConverter")
class JwtTenantConverterTest {

  private static final String CLAIM = "https://keystone.embracejoy.co/tenant_id";
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));
  private static final String SUB = "auth0|alice";

  @Mock TenantRepository tenants;
  @Mock TenantUserRoleRepository roles;
  @Mock PlatformAdminRepository platformAdmins;

  private TenantContext context;
  private JwtTenantConverter converter;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    context = new TenantContext();
    converter = new JwtTenantConverter(CLAIM, tenants, roles, platformAdmins, context);
  }

  @Test
  @DisplayName("valid JWT with tenant claim → ROLE_<role> + TenantContext set")
  void shouldGrantTenantRoleAndSetContext() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    when(roles.findRole(TENANT, SUB))
        .thenReturn(
            Optional.of(new TenantUserRole(TENANT, SUB, Role.ADMIN, Instant.now(), "auth0|root")));
    when(platformAdmins.exists(SUB)).thenReturn(false);
    Jwt jwt = jwt(SUB, Map.of(CLAIM, TENANT.value().toString()));

    AbstractAuthenticationToken auth = converter.convert(jwt);

    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    assertThat(context.require()).isEqualTo(TENANT);
  }

  @Test
  @DisplayName("unknown tenant → InvalidBearerTokenException")
  void shouldRejectUnknownTenant() {
    when(tenants.findById(TENANT)).thenReturn(Optional.empty());
    Jwt jwt = jwt(SUB, Map.of(CLAIM, TENANT.value().toString()));
    assertThrows(InvalidBearerTokenException.class, () -> converter.convert(jwt));
  }

  @Test
  @DisplayName("platform admin without tenant claim → only ROLE_PLATFORM_ADMIN, no context")
  void shouldGrantPlatformAdminAuthority() {
    when(platformAdmins.exists(SUB)).thenReturn(true);
    Jwt jwt = jwt(SUB, Map.of());
    AbstractAuthenticationToken auth = converter.convert(jwt);
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_PLATFORM_ADMIN");
    assertThat(context.current()).isEmpty();
  }

  @Test
  @DisplayName("no claim, not platform admin → empty authorities (controllers will 403)")
  void shouldReturnEmptyAuthoritiesWhenNeither() {
    when(platformAdmins.exists(SUB)).thenReturn(false);
    Jwt jwt = jwt(SUB, Map.of());
    AbstractAuthenticationToken auth = converter.convert(jwt);
    assertThat(auth.getAuthorities()).isEmpty();
    assertThat(context.current()).isEmpty();
  }

  @Test
  @DisplayName("tenant claim present, no role row → tenant set, no role authority")
  void shouldSetContextButNotGrantRoleWhenNoMembership() {
    when(tenants.findById(TENANT))
        .thenReturn(Optional.of(new Tenant(TENANT, "Acme", Instant.now(), Optional.empty())));
    when(roles.findRole(TENANT, SUB)).thenReturn(Optional.empty());
    Jwt jwt = jwt(SUB, Map.of(CLAIM, TENANT.value().toString()));
    AbstractAuthenticationToken auth = converter.convert(jwt);
    assertThat(auth.getAuthorities()).isEmpty();
    assertThat(context.require()).isEqualTo(TENANT);
  }

  private static Jwt jwt(String sub, Map<String, Object> claims) {
    Map<String, Object> all = new java.util.HashMap<>(claims);
    all.put("sub", sub);
    return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60),
                   Map.of("alg", "HS256"), all);
  }
}
```

- [ ] **Step 2: Run to fail.**

- [ ] **Step 3: Create `JwtTenantConverter`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Converts a validated {@link Jwt} into an authentication with tenant-scoped + platform-admin
 * authorities. Populates the request-scoped {@link TenantContext} as a side effect.
 */
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final String tenantClaim;
  private final TenantRepository tenants;
  private final TenantUserRoleRepository roles;
  private final PlatformAdminRepository platformAdmins;
  private final TenantContext tenantContext;

  public JwtTenantConverter(
      String tenantClaim,
      TenantRepository tenants,
      TenantUserRoleRepository roles,
      PlatformAdminRepository platformAdmins,
      TenantContext tenantContext) {
    this.tenantClaim = tenantClaim;
    this.tenants = tenants;
    this.roles = roles;
    this.platformAdmins = platformAdmins;
    this.tenantContext = tenantContext;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String sub = jwt.getSubject();
    Collection<GrantedAuthority> authorities = new ArrayList<>();

    if (platformAdmins.exists(sub)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    String tenantIdStr = jwt.getClaimAsString(tenantClaim);
    if (tenantIdStr != null) {
      TenantId tenantId;
      try {
        tenantId = new TenantId(UUID.fromString(tenantIdStr));
      } catch (IllegalArgumentException ex) {
        throw new InvalidBearerTokenException("malformed tenant claim", ex);
      }
      if (tenants.findById(tenantId).isEmpty()) {
        throw new InvalidBearerTokenException("unknown tenant: " + tenantId.value());
      }
      tenantContext.set(tenantId);
      roles.findRole(tenantId, sub)
          .ifPresent(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r.role().name())));
    }

    return new JwtAuthenticationToken(jwt, authorities, sub);
  }
}
```

- [ ] **Step 4: Run + commit**

---

## Task C3: `SecurityConfig` — API filter chain

**Files:**
- Create: `src/main/java/.../infrastructure/security/SecurityConfig.java`
- Modify: `application.yaml` (add issuer-uri + audience)

- [ ] **Step 1: Create the API SecurityFilterChain**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  @Order(2) // Phase D's UiSecurityConfig is @Order(1)
  SecurityFilterChain apiFilterChain(
      HttpSecurity http,
      KeystoneSecurityProperties properties,
      TenantRepository tenants,
      TenantUserRoleRepository roles,
      PlatformAdminRepository platformAdmins,
      TenantContext tenantContext) throws Exception {

    JwtTenantConverter converter =
        new JwtTenantConverter(
            properties.tenantClaim(), tenants, roles, platformAdmins, tenantContext);

    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/info",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .csrf(CsrfConfigurer::disable)
        .build();
  }
}
```

- [ ] **Step 2: Update `application.yaml`**

```yaml
keystone:
  security:
    issuer-uri: ${KEYSTONE_ISSUER_URI}
    audience: ${KEYSTONE_AUDIENCE}
    tenant-claim: "https://keystone.embracejoy.co/tenant_id"
    bootstrap-platform-admin-sub: ${KEYSTONE_PLATFORM_ADMIN_SUB:}
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYSTONE_ISSUER_URI}
          audiences: ${KEYSTONE_AUDIENCE}
```

- [ ] **Step 3: Update `KeystoneSecurityProperties`** with `@PostConstruct` validation:

```java
@PostConstruct
void validate() {
  if (issuerUri == null || issuerUri.isBlank()) {
    throw new IllegalStateException(
        "keystone.security.issuer-uri is required (set KEYSTONE_ISSUER_URI)");
  }
  if (audience == null || audience.isBlank()) {
    throw new IllegalStateException(
        "keystone.security.audience is required (set KEYSTONE_AUDIENCE)");
  }
}
```

- [ ] **Step 4: Delete `DefaultTenantFilter`** — no longer needed.

```
git rm src/main/java/co/embracejoy/accounting/keystone/infrastructure/security/DefaultTenantFilter.java
```

- [ ] **Step 5: Commit**

---

## Task C4: `SecurityExceptionHandler` — ProblemDetail for auth failures

**Files:**
- Create: `src/main/java/.../infrastructure/security/SecurityExceptionHandler.java`
- Create: `src/test/java/.../infrastructure/security/SecurityExceptionHandlerTest.java`

- [ ] **Step 1: Write the test**

Exercise the four mappings from spec §7.5: missing/invalid JWT → 401 + `/problems/auth/unauthenticated`; tenant present but not in DB → 403 + `/problems/auth/unknown-tenant` (already from `InvalidBearerTokenException`); valid JWT but no tenant claim and no platform admin role → 403 + `/problems/auth/missing-tenant` (`AccessDeniedException`); valid JWT + tenant + missing role → 403 + `/problems/auth/insufficient-role`.

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

@DisplayName("SecurityExceptionHandler")
class SecurityExceptionHandlerTest {

  private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

  @Test
  @DisplayName("missing/invalid JWT → 401 /problems/auth/unauthenticated")
  void shouldMap401() {
    ProblemDetail pd = handler.handle(new AuthenticationCredentialsNotFoundException("no auth"));
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(pd.getType().toString()).endsWith("/auth/unauthenticated");
  }

  @Test
  @DisplayName("InvalidBearerTokenException → 403 /problems/auth/unknown-tenant when message says so")
  void shouldMapInvalidBearerToUnknownTenant() {
    ProblemDetail pd = handler.handle(new InvalidBearerTokenException("unknown tenant: abc"));
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(pd.getType().toString()).endsWith("/auth/unknown-tenant");
  }

  @Test
  @DisplayName("AccessDeniedException → 403 /problems/auth/insufficient-role")
  void shouldMap403Role() {
    ProblemDetail pd = handler.handle(new AccessDeniedException("denied"));
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(pd.getType().toString()).endsWith("/auth/insufficient-role");
  }
}
```

- [ ] **Step 2: Create `SecurityExceptionHandler`**

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates Spring Security exceptions to RFC 9457 ProblemDetail responses. */
@RestControllerAdvice
public class SecurityExceptionHandler {

  private static final String BASE = "https://embracejoy.co/problems";

  @ExceptionHandler(InvalidBearerTokenException.class)
  public ProblemDetail handle(InvalidBearerTokenException ex) {
    String suffix = ex.getMessage() != null && ex.getMessage().contains("unknown tenant")
        ? "/auth/unknown-tenant"
        : "/auth/unauthenticated";
    HttpStatus status = suffix.equals("/auth/unknown-tenant")
        ? HttpStatus.FORBIDDEN
        : HttpStatus.UNAUTHORIZED;
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, sanitize(ex.getMessage()));
    pd.setType(URI.create(BASE + suffix));
    pd.setTitle(status == HttpStatus.UNAUTHORIZED ? "Unauthenticated" : "Unknown tenant");
    return pd;
  }

  @ExceptionHandler(AuthenticationException.class)
  public ProblemDetail handle(AuthenticationException ex) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, sanitize(ex.getMessage()));
    pd.setType(URI.create(BASE + "/auth/unauthenticated"));
    pd.setTitle("Unauthenticated");
    return pd;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handle(AccessDeniedException ex) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Insufficient role for resource");
    pd.setType(URI.create(BASE + "/auth/insufficient-role"));
    pd.setTitle("Insufficient role");
    return pd;
  }

  private static String sanitize(String s) {
    if (s == null) return "";
    String safe = s.replaceAll("\\p{Cntrl}", "?");
    return safe.length() > 200 ? safe.substring(0, 200) + "..." : safe;
  }
}
```

- [ ] **Step 3: Run + commit.**

---

## Task C5: `JwtTestSupport` helper

**Files:**
- Create: `src/test/java/.../infrastructure/security/JwtTestSupport.java`

A test utility that mints signed JWTs for use in `@WebMvcTest` and `ApplicationSmokeIT`. Uses Nimbus JOSE.

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Mints test JWTs signed by an in-memory RSA key. Pair with a NimbusJwtDecoder configured
 * against the same key in the test profile (see test-application.yaml).
 *
 * <p>Production code never references this class. Tests inject it as needed.
 */
public final class JwtTestSupport {

  private final RSAKey key;
  private final String issuer;
  private final String audience;
  private final String tenantClaim;

  public JwtTestSupport(RSAKey key, String issuer, String audience, String tenantClaim) {
    this.key = key;
    this.issuer = issuer;
    this.audience = audience;
    this.tenantClaim = tenantClaim;
  }

  /** A token for a tenant member with a specific role. */
  public String tokenFor(String sub, UUID tenantId) {
    return mint(sub, Map.of(tenantClaim, tenantId.toString()));
  }

  /** A token for a platform admin with no tenant claim. */
  public String tokenForPlatformAdmin(String sub) {
    return mint(sub, Map.of());
  }

  private String mint(String sub, Map<String, Object> extraClaims) {
    try {
      JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
          .subject(sub)
          .issuer(issuer)
          .audience(audience)
          .issueTime(Date.from(Instant.now()))
          .expirationTime(Date.from(Instant.now().plusSeconds(300)));
      extraClaims.forEach(builder::claim);
      SignedJWT jwt = new SignedJWT(
          new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
          builder.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("token mint failed", e);
    }
  }
}
```

A `JwtTestSupportConfig` `@TestConfiguration` provides this bean and configures `JwtDecoder` to validate tokens against the same key. Set up in `src/test/resources/application-test.yaml`.

Commit.

---

## Task C6-C9: `@PreAuthorize` on every existing controller

For each existing controller, add method-level `@PreAuthorize` per the permission matrix (spec §7.4):

**`AccountController`:**
- `GET /accounts`, `GET /accounts/{code}` → `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")`
- `POST /accounts`, `PATCH /accounts/{code}` → `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")`
- `POST /accounts/{code}/deactivate`, `/reactivate` → `@PreAuthorize("hasRole('ADMIN')")`

**`JournalEntryController`:** `POST /journal-entries` → `@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")`

**`PeriodController`:**
- `GET /periods*` → any of three roles
- `POST /periods/{ym}/close|reopen` → `@PreAuthorize("hasRole('ADMIN')")`

**`TrialBalanceController`:** `GET /reports/trial-balance` → any of three roles.

**Per-controller test updates:** every test now needs JWT setup. `@WebMvcTest` supports `with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))` via `SecurityMockMvcRequestPostProcessors.jwt()`. Add a `@MockitoBean TenantContext tenantContext` and stub `require()` to return a known tenant.

Each controller is its own task (C6 = Account, C7 = Journal, C8 = Period, C9 = TrialBalance). Each commit is one controller + its test. Body explains the permission gates added.

---

## Task C10: `PlatformAdminBootstrap`

**Files:**
- Create: `src/main/java/.../infrastructure/security/PlatformAdminBootstrap.java`

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * On boot, idempotently inserts the bootstrap platform admin (if {@code
 * keystone.security.bootstrap-platform-admin-sub} is set). Solves the chicken-and-egg problem
 * for fresh deployments: the bootstrap admin can then create tenants via API/UI.
 */
@Configuration
public class PlatformAdminBootstrap {

  private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

  @Bean
  ApplicationRunner bootstrapPlatformAdmin(
      KeystoneSecurityProperties properties, PlatformAdminRepository repository) {
    return args -> {
      String sub = properties.bootstrapPlatformAdminSub();
      if (sub == null || sub.isBlank()) {
        log.info("no KEYSTONE_PLATFORM_ADMIN_SUB set; skipping platform-admin bootstrap");
        return;
      }
      repository.grant(sub);
      log.info("bootstrap platform admin granted to sub={}", sub);
    };
  }
}
```

Commit.

---

## Task C11: Phase C acceptance

```
./mvnw -B clean verify -Pmutation,openapi-gate -Dopenapi.diff.skip=true
```

Expect: BUILD SUCCESS. Coverage gates pass.

**Update ApplicationSmokeIT:** add JWT-protected smoke. The existing tests mint JWTs via `JwtTestSupport`; ensure they pass through the security chain end-to-end.

Push branch, open PR `Slice 5 Phase C: OAuth2 resource server + RBAC`.


---

# Phase D — Admin REST API + Thymeleaf+HTMX UI + smoke + close #16

Phase D adds the tenant/user management surface (REST + UI), a separate `SecurityFilterChain` for the UI (OAuth2 Authorization Code + PKCE), the smoke updates that exercise tenant CRUD end-to-end, the OpenAPI regen, and the README/CLAUDE.md updates that close out the slice. ~12 tasks.

---

## Task D1: Add Thymeleaf + OAuth2 client dependencies

**Files:**
- Modify: `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

Compile + commit. (Spring Boot auto-config will try to set up an OAuth2 client; until you add registration entries to `application.yaml` in Task D6 it may log warnings — that's OK, no failure.)

---

## Task D2: `TenantController` REST endpoints

**Files:**
- Create: `src/main/java/.../infrastructure/web/admin/TenantController.java`
- Create: `src/main/java/.../infrastructure/web/admin/dto/CreateTenantRequest.java`
- Create: `src/main/java/.../infrastructure/web/admin/dto/TenantResponse.java`
- Create: `src/test/java/.../infrastructure/web/admin/TenantControllerTest.java`

**`CreateTenantRequest` record:** `@NotBlank @Size(max=200) String name`.

**`TenantResponse` record:** `(String id, String name, Instant createdAt, Optional<Instant> deactivatedAt, boolean active)`. Static `of(Tenant)` factory.

**`TenantController`:**

```java
@RestController
@RequestMapping("/admin/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class TenantController {

  private final TenantService service;

  public TenantController(TenantService service) { this.service = service; }

  @PostMapping
  public ResponseEntity<?> create(@Valid @RequestBody CreateTenantRequest req) {
    return service.create(req.name())
        .fold(
            t -> ResponseEntity.created(URI.create("/admin/tenants/" + t.id().value()))
                    .body(TenantResponse.of(t)),
            err -> ResponseEntity.badRequest()
                    .contentType(MediaType.parseMediaType("application/problem+json"))
                    .body(TenantErrorMapper.toProblemDetail(err)));
  }

  @GetMapping
  public List<TenantResponse> list() {
    return service.findAll().stream().map(TenantResponse::of).toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
    return service.findById(new TenantId(id))
        .map(t -> ResponseEntity.ok(TenantResponse.of(t)))
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> deactivate(@PathVariable UUID id) {
    return service.deactivate(new TenantId(id))
        .fold(
            t -> ResponseEntity.ok(TenantResponse.of(t)),
            err -> /* map to ProblemDetail */ );
  }
}
```

Add `TenantErrorMapper` (small helper translating `TenantError` variants to ProblemDetail per spec §5.3 conventions).

**Test (`TenantControllerTest`):** `@WebMvcTest`, `@MockitoBean TenantService`, `with(jwt().authorities(...))` for `ROLE_PLATFORM_ADMIN`. Test 201 happy path, 400 on InvalidName, 404 on get unknown, 200 on deactivate, 403 when missing platform-admin role.

Run + commit.

---

## Task D3: `UserRoleController` REST endpoints

**Files:**
- Create: `src/main/java/.../infrastructure/web/admin/UserRoleController.java`
- Create: `src/main/java/.../infrastructure/web/admin/dto/AssignRoleRequest.java`
- Create: `src/main/java/.../infrastructure/web/admin/dto/TenantUserRoleResponse.java`
- Create: `src/test/java/.../infrastructure/web/admin/UserRoleControllerTest.java`

**`AssignRoleRequest`:** `@NotBlank @Pattern(regexp = "^(ADMIN|BOOKKEEPER|READ_ONLY)$") String role`.

**`TenantUserRoleResponse`:** `(String tenantId, String userSub, String role, Instant grantedAt, String grantedBy)`. Static `of(TenantUserRole)`.

**`UserRoleController`:**

```java
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserRoleController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  // constructor

  @GetMapping
  public List<TenantUserRoleResponse> list() {
    return service.findByTenant(tenantContext.require()).stream()
        .map(TenantUserRoleResponse::of)
        .toList();
  }

  @PutMapping("/{userSub}")
  public ResponseEntity<?> grant(
      @PathVariable String userSub,
      @Valid @RequestBody AssignRoleRequest req,
      @AuthenticationPrincipal Jwt jwt) {
    return service.grant(tenantContext.require(), userSub, Role.valueOf(req.role()), jwt.getSubject())
        .fold(
            row -> ResponseEntity.ok(TenantUserRoleResponse.of(row)),
            err -> /* map */ );
  }

  @DeleteMapping("/{userSub}")
  public ResponseEntity<?> revoke(@PathVariable String userSub, @AuthenticationPrincipal Jwt jwt) {
    return service.revoke(tenantContext.require(), userSub, jwt.getSubject())
        .fold(
            v -> ResponseEntity.noContent().<Object>build(),
            err -> /* map */ );
  }
}
```

`SecurityErrorMapper` translates `SecurityError` variants (CannotOrphanSelf → 400 `/problems/admin/cannot-orphan-self`, RoleNotFound → 404, etc.).

Test: PUT 200 happy path, PUT 400 on CannotOrphanSelf, DELETE 204 happy, DELETE 404 on unknown, GET lists rows for current tenant, all forbid non-Admin callers.

Run + commit.

---

## Task D4: Wire `TenantRepositoryAdapter` to platform DataSource

The Phase B Task B8/B10 created the JPA repos but bound them to the `@Primary` DataSource. Phase D moves them to the platform DataSource via a separate `@EnableJpaRepositories` package scan.

**Files:**
- Create: `src/main/java/.../infrastructure/config/PlatformPersistenceConfig.java`

```java
package co.embracejoy.accounting.keystone.infrastructure.config;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableJpaRepositories(
    basePackages = {
        "co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy",
        "co.embracejoy.accounting.keystone.infrastructure.persistence.security"
    },
    entityManagerFactoryRef = "platformEntityManagerFactory",
    transactionManagerRef = "platformTransactionManager")
public class PlatformPersistenceConfig {

  @Bean
  LocalContainerEntityManagerFactoryBean platformEntityManagerFactory(
      EntityManagerFactoryBuilder builder, @Qualifier("platformDataSource") DataSource dataSource) {
    return builder.dataSource(dataSource)
        .packages(
            "co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy",
            "co.embracejoy.accounting.keystone.infrastructure.persistence.security")
        .persistenceUnit("platform")
        .build();
  }

  @Bean
  PlatformTransactionManager platformTransactionManager(
      @Qualifier("platformEntityManagerFactory") EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }
}
```

The `@Primary` (app) `EntityManagerFactory` continues to scan the `domain.account.*`, `domain.journal.*`, `domain.period.*` packages.

Adjust the existing main `EntityManagerFactory` config to scan only the app-side packages (exclude `tenancy`, `security`).

Existing `TenantRepositoryAdapter` and `PlatformAdminRepositoryAdapter` automatically pick up the platform `EntityManagerFactory` because they live in the `tenancy`/`security` packages.

`TenantUserRoleRepositoryAdapter` is the tricky one — it needs the platform pool to write rows (because the `tenant_user_roles` table HAS RLS enabled, but the platform admin needs to manage roles across tenants). Decision: tenant-admin operations on `tenant_user_roles` happen through the app pool (subject to RLS, which is correct — admins only see their own tenant's users). Platform-admin operations don't touch this table. So `TenantUserRoleRepositoryAdapter` stays on the app pool.

Move `TenantUserRoleRepositoryAdapter` to a new package OR mark it explicitly with `@Qualifier`. Cleanest: keep `tenant_user_roles` in the **app** persistence config, only `tenants` and `platform_admins` in the platform config. Update `PlatformPersistenceConfig` to scan only `tenancy.*` and the platform-admin-specific package — split the security package into `security.tenantroles` (app pool) and `security.platformadmins` (platform pool).

Refactor accordingly. Commit.

---

## Task D5: `UiSecurityConfig` — UI filter chain (OAuth2 client / PKCE)

**Files:**
- Create: `src/main/java/.../infrastructure/security/UiSecurityConfig.java`

```java
package co.embracejoy.accounting.keystone.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class UiSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain uiFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/admin/ui/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2Login(o -> o.defaultSuccessUrl("/admin/ui/users", true))
        .logout(l -> l.logoutSuccessUrl("/admin/ui/login"))
        .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
        .build();
  }
}
```

The OAuth2 client registration is pulled from `application.yaml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keystone:
            provider: keystone
            client-id: ${KEYSTONE_OAUTH2_CLIENT_ID}
            client-secret: ${KEYSTONE_OAUTH2_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile, email
        provider:
          keystone:
            issuer-uri: ${KEYSTONE_ISSUER_URI}
            user-name-attribute: sub
```

Commit.

---

## Task D6: Vendor HTMX + Bootstrap, create layout template

**Files:**
- Create: `src/main/resources/static/htmx.min.js` (download from htmx.org/dist/2.0.x/htmx.min.js)
- Create: `src/main/resources/static/bootstrap.min.css` (download from bootstrap CDN)
- Create: `src/main/resources/templates/admin/layout.html`
- Create: `src/main/resources/templates/admin/login.html`

```html
<!-- src/main/resources/templates/admin/layout.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head>
    <meta charset="UTF-8">
    <title layout:title-pattern="$LAYOUT_TITLE - Keystone">Keystone Admin</title>
    <link rel="stylesheet" th:href="@{/bootstrap.min.css}">
    <script th:src="@{/htmx.min.js}" defer></script>
    <meta name="_csrf" th:content="${_csrf.token}"/>
    <meta name="_csrf_header" th:content="${_csrf.headerName}"/>
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark bg-dark">
    <div class="container">
        <a class="navbar-brand" href="/admin/ui/">Keystone</a>
        <ul class="navbar-nav me-auto">
            <li class="nav-item"><a class="nav-link" href="/admin/ui/users">Users</a></li>
            <li class="nav-item" sec:authorize="hasRole('PLATFORM_ADMIN')">
                <a class="nav-link" href="/admin/ui/tenants">Tenants</a>
            </li>
            <li class="nav-item"><a class="nav-link" href="/admin/ui/profile">Profile</a></li>
        </ul>
        <form th:action="@{/logout}" method="post">
            <button type="submit" class="btn btn-outline-light">Logout</button>
        </form>
    </div>
</nav>
<main class="container py-4" layout:fragment="content"></main>
<script>
// Wire HTMX to send the CSRF header on every request.
document.addEventListener('DOMContentLoaded', () => {
    const token = document.querySelector('meta[name="_csrf"]').content;
    const header = document.querySelector('meta[name="_csrf_header"]').content;
    document.body.addEventListener('htmx:configRequest', e => {
        e.detail.headers[header] = token;
    });
});
</script>
</body>
</html>
```

Add the Spring Security `thymeleaf-extras-springsecurity6` dependency in `pom.xml` for `sec:authorize`:

```xml
<dependency>
    <groupId>org.thymeleaf.extras</groupId>
    <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
```

`login.html` is minimal — a "Sign in with your IdP" button that POSTs to `/oauth2/authorization/keystone`.

Commit.

---

## Task D7: Users UI controller + templates

**Files:**
- Create: `src/main/java/.../infrastructure/web/ui/UsersUiController.java`
- Create: `src/main/resources/templates/admin/users.html`
- Create: `src/main/resources/templates/admin/fragments/user-row.html`

Controller:

```java
@Controller
@RequestMapping("/admin/ui/users")
@PreAuthorize("hasRole('ADMIN')")
public class UsersUiController {

  private final UserRoleService service;
  private final TenantContext tenantContext;

  @GetMapping
  public String list(Model model) {
    model.addAttribute("rows", service.findByTenant(tenantContext.require()));
    return "admin/users";
  }

  @PutMapping("/{userSub}")
  public String grant(
      @PathVariable String userSub,
      @RequestParam String role,
      @AuthenticationPrincipal OAuth2User principal,
      Model model) {
    var result = service.grant(
        tenantContext.require(), userSub, Role.valueOf(role), principal.getAttribute("sub"));
    // On error, render a flash message; on success, render the updated row fragment.
    return "admin/fragments/user-row";
  }

  @DeleteMapping("/{userSub}")
  public ResponseEntity<String> revoke(
      @PathVariable String userSub,
      @AuthenticationPrincipal OAuth2User principal) {
    service.revoke(tenantContext.require(), userSub, principal.getAttribute("sub"));
    return ResponseEntity.ok(""); // empty response → HTMX swap=outerHTML removes the row
  }
}
```

`users.html`: a Bootstrap table with one row per assignment. Each row has an HTMX-driven `<select>` for changing role and a Delete button. Insert form at the top for adding a new user.

`fragments/user-row.html`: the single-row template returned by HTMX after a PUT (out-of-band swap).

Commit.

---

## Task D8: Tenants UI controller + templates (PLATFORM_ADMIN only)

**Files:**
- Create: `src/main/java/.../infrastructure/web/ui/TenantsUiController.java`
- Create: `src/main/resources/templates/admin/tenants.html`
- Create: `src/main/resources/templates/admin/tenant-detail.html`

Mirrors Users UI but for `TenantService`. List, create form, detail page with Deactivate button. `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` on the controller.

Commit.

---

## Task D9: Profile UI

**Files:**
- Create: `src/main/java/.../infrastructure/web/ui/ProfileUiController.java`
- Create: `src/main/resources/templates/admin/profile.html`

Read-only page showing: current user sub, current tenant id + name, current role. Useful for "what am I logged in as?" debugging.

```java
@Controller
@RequestMapping("/admin/ui/profile")
public class ProfileUiController {

  @GetMapping
  public String profile(@AuthenticationPrincipal OAuth2User principal, Model model,
      TenantContext tenantContext, TenantRepository tenants, UserRoleService roleService) {
    model.addAttribute("sub", principal.getAttribute("sub"));
    tenantContext.current().ifPresent(tid -> {
      tenants.findById(tid).ifPresent(t -> model.addAttribute("tenant", t));
      roleService.findRole(tid, principal.getAttribute("sub"))
          .ifPresent(r -> model.addAttribute("role", r.role()));
    });
    return "admin/profile";
  }
}
```

Commit.

---

## Task D10: ApplicationSmokeIT — auth + tenancy end-to-end

Update existing smoke tests to mint JWTs via `JwtTestSupport`, attach to every request as `Authorization: Bearer <token>`. Add new smoke tests:

1. **Bootstrap-via-env-var**: set `KEYSTONE_PLATFORM_ADMIN_SUB` test fixture; verify the row exists in `platform_admins` after boot.
2. **Tenant CRUD**: POST `/admin/tenants` as platform admin; verify 201; GET listing shows it.
3. **Role grant + posting**: grant ADMIN to a test user in the new tenant; mint a JWT for that user with the tenant claim; POST `/journal-entries` succeeds.
4. **Two-tenant isolation**: post entries in tenants A and B; query trial balance with each user's JWT; assert each sees only their own.
5. **403 on wrong role**: mint a READ_ONLY token; POST `/journal-entries` returns 403 + `/problems/auth/insufficient-role`.
6. **403 on missing tenant claim**: mint a JWT with no claim and not platform admin; any tenant-scoped endpoint returns 403 + `/problems/auth/missing-tenant`.

Commit.

---

## Task D11: Regenerate OpenAPI snapshot

```bash
./mvnw -B verify -Popenapi-update -Dopenapi.diff.skip=true
```

The diff vs main will be huge — every existing endpoint now requires bearer auth (security scheme added globally), plus new admin endpoints. This is intentional and breaking; the PR carries the `breaking-change-approved` label.

Spot-check the generated spec:
- Security scheme `bearerJwt` present
- Every existing endpoint has `security:` referencing it
- New endpoints: `/admin/tenants`, `/admin/users` and their methods
- ProblemDetail responses for 401 / 403 with the four problem URIs

Commit.

---

## Task D12: README + CLAUDE.md updates; close #16

**README.md:**
- Status row: `- [x] Slice 5 — tenancy + RBAC + OAuth2 (#16)`
- New "Auth" section after Quick Start: how to set the env vars, how to mint a test JWT for local dev, link to ADR-0017.

**CLAUDE.md:**
- New convention bullet: "Tenancy is hybrid app-filter + Postgres RLS. Domain aggregates carry `TenantId` as the first record component. Every adapter reads `TenantContext`. Platform admins use a separate BYPASSRLS DataSource. See [ADR-0016](docs/adr/0016-multi-tenant-row-level-isolation.md), [ADR-0017](docs/adr/0017-oauth2-resource-server-with-jwt-tenant-claim.md)."
- Update Quick Reference if any new mvn target is added.

**Commit (closes #16):**

```
git add README.md CLAUDE.md docs/openapi/openapi.yaml
git commit -m "docs: Slice 5 done — flip status, auth conventions, regenerate OpenAPI

Closes #16"
```

---

## Phase D acceptance + slice acceptance

Final cold-cache verify with all gates:

```
./mvnw -B clean verify -Pmutation,openapi-gate -Dopenapi.diff.skip=true
```

Open Phase D PR with title `Slice 5 Phase D: admin API + UI + smoke (closes #16)`. Apply `breaking-change-approved` label so openapi-diff Layer 4 doesn't block (every endpoint now requires bearer JWT — that's the breaking change the spec authorizes).

After merge:
- Issue #16 closes.
- `main` is fully secured.
- Default tenant remains for standalone deployments.
- Slice 5 acceptance criteria (spec §12) verified end-to-end.

---

# Self-review checklist

Run after the plan is fully drafted:

1. **Spec coverage**: each spec section has at least one task implementing it.
2. **Placeholder scan**: no TBD/TODO/handle-edge-cases. The "follow Task BX pattern" references in B5/B6 are acceptable because B4 is the worked example with full code.
3. **Type consistency**: `TenantId`, `Role`, `Permission`, `TenantContext.require()`, `JwtTenantConverter` — all consistent across phases.
4. **ADRs**: 0015 (URL versioning), 0016 (RLS), 0017 (OAuth2) all created in Phase A.
5. **Acceptance**: spec §12 criteria mapped to acceptance steps in each phase.

# Phase boundary recap

| Phase | Lands on main as | Interim state |
|---|---|---|
| A | New tenancy + security domain types only | Build green; no behavior change |
| B | Tenant-aware persistence + RLS | API still unauthenticated; default tenant filter populates context |
| C | OAuth2 + RBAC | All endpoints require JWT; admin endpoints not yet implemented |
| D | Admin API + UI + close #16 | Slice 5 complete |

# Slice 5 overall acceptance (mirrors spec §12)

1. `./mvnw -B clean verify -Pmutation,openapi-gate` green on every phase PR (Phases B-D use `-Dopenapi.diff.skip=true` or the `breaking-change-approved` label).
2. `POST /journal-entries` requires a JWT; without one, 401.
3. JWT for tenant A cannot read or write tenant B's data — verified at both layers (`RowLevelSecurityIT`).
4. JWT with no tenant claim and no platform-admin grant gets 403 `/problems/auth/missing-tenant`.
5. JWT with insufficient role gets 403 `/problems/auth/insufficient-role`.
6. `POST /admin/tenants` requires platform admin; tenant Admin gets 403.
7. Thymeleaf admin UI loads, walks operator through the OAuth2 login redirect, lets a tenant Admin grant a role.
8. `KEYSTONE_PLATFORM_ADMIN_SUB` env var bootstraps idempotently.
9. ADR-0015 / 0016 / 0017 ship with the slice.
10. `docs/openapi/openapi.yaml` regenerated; PR carries `breaking-change-approved` label.
11. Issue #16 closes.
