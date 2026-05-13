# Slice 5 — Tenancy + RBAC + OAuth2 Resource Server

> Status: design approved 2026-05-13. Implementation plan to follow.
> Tracking: [#16](https://github.com/robsartin/keystone/issues/16).

---

## 1. Context

Slices 1–4 + 6 built a single-tenant general ledger: chart of accounts, journal
entries, periods, multi-currency, and the trial-balance read endpoint. Every
table is single-tenant by accident — the schema has no tenant dimension and
no authentication is required to call any endpoint.

Slice 5 turns keystone into a multi-tenant OAuth2-protected service. After
this slice ships:

- Every business row carries a `tenant_id`. Cross-tenant data access is
  impossible — enforced both by application filtering and by Postgres
  Row-Level Security policies.
- Every endpoint requires a valid JWT issued by an external IdP (Auth0,
  Cognito, Okta, Keycloak, Google — operator's choice).
- The JWT carries the user's tenant in a custom claim. Keystone validates
  the claim against its `tenants` table and routes the request accordingly.
- Three tenant-scoped roles (Admin, Bookkeeper, ReadOnly) gate per-endpoint
  permissions, plus a separate Platform Admin role above the tenant boundary
  for tenant CRUD.
- A small Thymeleaf+HTMX admin UI lets tenant admins manage users and
  platform admins manage tenants — without requiring an external
  frontend project.
- Standalone single-tenant deployments still work cleanly: a
  default tenant exists from the migration, and a single env var
  bootstraps the first platform admin.

## 2. Goals

- Multi-tenant data isolation at two layers (application + Postgres RLS).
- Resource-server JWT validation; no IdP code in keystone itself.
- Tenant-scoped RBAC (3 roles) + platform-admin role for tenant CRUD.
- Platform-admin-bootstrap-by-env-var so a fresh deployment has a clear
  first-admin path.
- Admin API + minimal Thymeleaf+HTMX UI for tenant/user management.
- ADR codifying "no URL versioning" — explicit policy now that every
  endpoint moves under auth.
- Backward compatibility for single-tenant deployments: the V6 migration
  creates a default tenant and backfills all existing rows to it.

## 3. Non-goals (explicit)

- Self-service tenant signup. Platform-admin-only via API.
- Refresh tokens, token introspection, opaque tokens. JWT-only.
- An audit log table or audit-log viewer (we capture `granted_by` per role
  row but don't surface it).
- MFA, password policy, IdP user store. Operator's IdP problem.
- Per-account ACLs ("user X can post to account 1000 but not 3000"). The
  granularity is tenant + role.
- Cross-tenant reports for platform admins.
- Tenant-scoped configuration (e.g., per-tenant base currency). Global only.
- Theming, i18n, dark mode for the admin UI. Bootstrap defaults only.
- Bundled IdP in `docker-compose.yml`. Operators bring their own.
- Self-hosted authorization server (Spring Authorization Server). Resource
  server only.

## 4. Architecture

### 4.1 Hexagonal package additions

```
domain/tenancy/                        Tenant record, TenantId, TenantError
domain/security/                       Role enum, Permission enum,
                                       UserPrincipal record,
                                       TenantUserRole record,
                                       SecurityError sealed type

application/tenancy/                   TenantService (platform-admin CRUD)
application/security/                  UserRoleService (per-tenant grants)

infrastructure/persistence/tenancy/    JpaTenantRepository, adapter, entity
infrastructure/persistence/security/   JpaTenantUserRoleRepository,
                                       JpaPlatformAdminRepository

infrastructure/web/admin/              TenantController, UserRoleController,
                                       DTOs
infrastructure/web/ui/                 Thymeleaf controllers for /admin/ui/*

infrastructure/security/               OAuth2 resource-server config,
                                       OAuth2 client config (UI),
                                       TenantContext (request-scoped bean),
                                       JwtTenantConverter,
                                       SecurityExceptionHandler,
                                       method-security expression handler
```

### 4.2 Cross-cutting changes (every existing repository)

- Domain records `Account`, `JournalEntry`, `Period` gain a `tenantId`
  field. Compact constructors validate it. Equality and hashCode include it.
  This is a breaking change to every existing test fixture (~30 sites,
  mechanical).
- Repository ports stay the same shape, but the **adapter** implementations
  read `TenantContext` to scope every query/insert. JPA repositories use
  `findByTenantIdAnd*` derived queries; `JdbcClient` queries explicitly
  include `:tenantId` as a named parameter.
- The `TrialBalanceJdbcReadModel` SQL gains `WHERE p.tenant_id = :tenantId`
  in addition to its existing `WHERE je.occurred_on <= :asOf`.

### 4.3 New ArchUnit rules

- Every domain aggregate-root record (`Account`, `JournalEntry`, `Period`,
  `Tenant`, `TenantUserRole`) must have a `tenantId()` accessor. Reflection
  check across `domain.*`.
- Every `@Repository` must either inject `TenantContext` or have
  `TenantId` as a method parameter. Reflection across
  `infrastructure.persistence.*`.
- No `@RestController` may map a non-actuator non-docs endpoint without
  going through one of the two `SecurityFilterChain`s. (Detected by:
  every `@RequestMapping` path must match a security matcher.)

## 4a. URL versioning policy (ADR-0015)

Keystone APIs are versionless at the URL level. There will never be `/v1/`
or `/v2/` paths. Breaking changes are caught at PR time by the four-layer
OpenAPI gate (Spectral + snapshot diff + openapi-diff vs `main` + the
`breaking-change-approved` label flow already used in Slice 6 Phase C).
When unavoidable, breaking changes are announced in release notes and an
ADR.

If a versioned compatibility window ever becomes necessary later, it will
be header-based (`API-Version: YYYY-MM-DD`, Stripe-style) — never
URL-prefixed. Not implemented now.

ADR-0015 ships with this slice and documents the rationale.

## 5. Data model & migrations

A single Flyway migration `V6__tenancy_and_rbac.sql` introduces:

### 5.1 New tables

```sql
CREATE TABLE tenants (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deactivated_at  TIMESTAMPTZ
);

CREATE TABLE tenant_user_roles (
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_sub     VARCHAR(255) NOT NULL,                -- IdP `sub` claim
    role         VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN','BOOKKEEPER','READ_ONLY')),
    granted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by   VARCHAR(255) NOT NULL,                -- IdP `sub` of granter
    PRIMARY KEY (tenant_id, user_sub)
);
CREATE INDEX idx_tenant_user_roles_user ON tenant_user_roles (user_sub);

CREATE TABLE platform_admins (
    user_sub    VARCHAR(255) PRIMARY KEY,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 5.2 Default tenant + backfill

```sql
-- The UUID is generated at build time (stable, written into both the
-- migration and application.yaml's keystone.default-tenant-id).
INSERT INTO tenants (id, name) VALUES ('${default.tenant.id}', 'Default Tenant');

ALTER TABLE accounts        ADD COLUMN tenant_id UUID;
ALTER TABLE journal_entries ADD COLUMN tenant_id UUID;
ALTER TABLE postings        ADD COLUMN tenant_id UUID;
ALTER TABLE periods         ADD COLUMN tenant_id UUID;

UPDATE accounts        SET tenant_id = '${default.tenant.id}';
UPDATE journal_entries SET tenant_id = '${default.tenant.id}';
UPDATE postings        SET tenant_id = '${default.tenant.id}';
UPDATE periods         SET tenant_id = '${default.tenant.id}';

ALTER TABLE accounts        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE journal_entries ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE postings        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE periods         ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE accounts        ADD CONSTRAINT fk_accounts_tenant        FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE journal_entries ADD CONSTRAINT fk_journal_entries_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE postings        ADD CONSTRAINT fk_postings_tenant        FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE periods         ADD CONSTRAINT fk_periods_tenant         FOREIGN KEY (tenant_id) REFERENCES tenants(id);
```

### 5.3 Composite primary keys (per-tenant uniqueness)

Account codes and period year-months are unique **per tenant**, not globally:

```sql
ALTER TABLE accounts DROP CONSTRAINT accounts_pkey;
ALTER TABLE accounts ADD PRIMARY KEY (tenant_id, code);

ALTER TABLE periods DROP CONSTRAINT periods_pkey;
ALTER TABLE periods ADD PRIMARY KEY (tenant_id, year_month);

-- Foreign keys become composite to preserve cross-tenant referential integrity:
ALTER TABLE postings DROP CONSTRAINT postings_account_code_fkey;
ALTER TABLE postings ADD CONSTRAINT postings_account_code_fkey
    FOREIGN KEY (tenant_id, account_code) REFERENCES accounts(tenant_id, code);
```

### 5.4 Indexes (tenant_id leads every query plan)

```sql
CREATE INDEX idx_accounts_tenant_code      ON accounts        (tenant_id, code);
CREATE INDEX idx_periods_tenant_year_month ON periods         (tenant_id, year_month);
CREATE INDEX idx_journal_entries_tenant    ON journal_entries (tenant_id, occurred_on);
CREATE INDEX idx_postings_tenant_account   ON postings        (tenant_id, account_code);
```

### 5.5 Domain consequences

- `Account`, `Period`, `JournalEntry` records gain a `TenantId tenantId`
  field as the first record component. Equality includes it. Persistence
  mappers preserve it.
- `AccountCode` stays as the single-string natural key inside a tenant; a
  full identity is the pair `(TenantId, AccountCode)`. The repository ports
  use `TenantContext` to supply the tenant; callers continue to pass
  `AccountCode` only.
- The same applies to `Period` (key = `(TenantId, YearMonth)`).

## 6. Isolation enforcement (two layers)

### 6.1 Layer 1 — application filter

A request-scoped `TenantContext` bean holds the current tenant:

```java
@Component
@RequestScope
public class TenantContext {
  private TenantId tenantId;          // set by JwtTenantConverter on each request

  public TenantId require() {
    if (tenantId == null) throw new IllegalStateException("no tenant in context");
    return tenantId;
  }
  // package-private setter, called only by the security filter
}
```

Repository adapters read it on every operation:

```java
@Repository
@Transactional
public class AccountRepositoryAdapter implements AccountRepository {
  private final JpaAccountRepository jpa;
  private final TenantContext tenantContext;

  @Override
  public Result<Account, AccountError> save(Account account) {
    TenantId t = tenantContext.require();
    if (!t.equals(account.tenantId())) {
      throw new IllegalStateException(
          "tenant mismatch — domain says " + account.tenantId() + ", context says " + t);
    }
    // ... jpa.save() — the entity carries tenant_id
  }

  @Override
  public Optional<Account> findByCode(AccountCode code) {
    return jpa.findByTenantIdAndCode(tenantContext.require().value(), code.value())
        .map(AccountEntityMapper::toDomain);
  }
}
```

The "tenant mismatch" guard catches programming errors loud and early —
if domain code is constructed with the wrong tenant, we fail at save time
rather than write a bad row.

### 6.2 Layer 2 — Postgres Row-Level Security

```sql
ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY accounts_tenant_isolation ON accounts
  USING      (tenant_id = current_setting('app.current_tenant', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.current_tenant', true)::uuid);

-- Same shape for journal_entries, postings, periods, tenant_user_roles.
-- tenants and platform_admins are NOT RLS-enabled — they need cross-tenant
-- visibility for platform admins.
```

A Spring `HandlerInterceptor` (or `TransactionalEventListener`) runs
`SET LOCAL app.current_tenant = '<uuid>'` at the start of each transaction.
The `true` second arg to `current_setting()` makes it return `NULL` when
unset; `tenant_id = NULL::uuid` is false, so an unset GUC means **zero
rows returned, ever**, never cross-tenant.

### 6.3 Two DataSources for the BYPASSRLS escape hatch

Platform-admin operations (tenant CRUD) need to span tenants. We use a
separate Postgres role `keystone_platform` with `BYPASSRLS` granted, used
only by a separate connection pool serving platform-admin endpoints.

```yaml
keystone:
  datasource:
    app:                                   # tenant-scoped pool, no BYPASSRLS
      url: ${DATABASE_URL}
      username: keystone
      password: ${DATABASE_PASSWORD}
    platform:                              # platform-admin pool, has BYPASSRLS
      url: ${DATABASE_URL}
      username: keystone_platform
      password: ${KEYSTONE_PLATFORM_DB_PASSWORD}
```

Two Spring `EntityManagerFactory` instances, two `@EnableJpaRepositories`
package scans. The platform pool is small (max 2 connections — admin ops
are rare). The default app pool keeps its current sizing.

### 6.4 Verification

Both layers have dedicated tests. See §10.4.

## 7. Authentication & authorization

### 7.1 Two SecurityFilterChain beans

```java
@Order(1)
@Bean
SecurityFilterChain uiFilterChain(HttpSecurity http) throws Exception {
  return http
      .securityMatcher("/admin/ui/**")
      .authorizeHttpRequests(a -> a.anyRequest().authenticated())
      .oauth2Login(o -> o
          .userInfoEndpoint(u -> u.userAuthoritiesMapper(rolesMapper))
          .defaultSuccessUrl("/admin/ui/users", true))
      .logout(l -> l.logoutSuccessUrl("/admin/ui/login"))
      .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
      .build();
}

@Order(2)
@Bean
SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
  return http
      .authorizeHttpRequests(a -> a
          .requestMatchers("/actuator/health", "/actuator/info",
                          "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
          .anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtTenantConverter)))
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .csrf(CsrfConfigurer::disable)
      .build();
}
```

`/actuator/prometheus` is **not** in the permitAll list. Operators who want
unauthenticated metrics for Prometheus scrape can override the security
config in their deployment.

### 7.2 JwtTenantConverter

Translates a JWT into Spring Security authorities and populates
`TenantContext`:

```java
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {
  private final String tenantClaim;       // default "https://keystone.embracejoy.co/tenant_id"
  private final TenantRepository tenants;
  private final TenantUserRoleRepository roles;
  private final PlatformAdminRepository platformAdmins;
  private final TenantContext tenantContext;

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String sub = jwt.getSubject();
    Collection<GrantedAuthority> authorities = new ArrayList<>();

    if (platformAdmins.exists(sub)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    String tenantIdStr = jwt.getClaimAsString(tenantClaim);
    if (tenantIdStr != null) {
      TenantId tenantId = new TenantId(UUID.fromString(tenantIdStr));
      if (tenants.findById(tenantId).isEmpty()) {
        throw new InvalidBearerTokenException("unknown tenant");
      }
      tenantContext.set(tenantId);
      roles.findRole(tenantId, sub)
          .ifPresent(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
    return new JwtAuthenticationToken(jwt, authorities, sub);
  }
}
```

### 7.3 Per-endpoint role gating

Method-level `@PreAuthorize`:

```java
@PostMapping("/journal-entries")
@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
public ResponseEntity<?> postEntry(...) { ... }

@PostMapping("/periods/{yyyymm}/close")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<?> closePeriod(...) { ... }

@GetMapping("/reports/trial-balance")
@PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
public List<TrialBalanceRowResponse> get(...) { ... }

@PostMapping("/admin/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public ResponseEntity<?> createTenant(...) { ... }
```

`@EnableMethodSecurity` is on the security config. `AccessDeniedException`
is mapped to ProblemDetail by `SecurityExceptionHandler` (a new class —
keeps validation and security concerns separate).

### 7.4 Permission matrix (canonical)

| Endpoint | ReadOnly | Bookkeeper | Admin | PlatformAdmin |
|---|:-:|:-:|:-:|:-:|
| `GET /accounts`, `GET /accounts/{code}` | ✅ | ✅ | ✅ | — |
| `POST /accounts`, `PATCH /accounts/{code}` (rename/reparent) | — | ✅ | ✅ | — |
| `POST /accounts/{code}/deactivate`, `/reactivate` | — | — | ✅ | — |
| `POST /journal-entries` | — | ✅ | ✅ | — |
| `GET /periods`, `GET /periods/{yyyymm}` | ✅ | ✅ | ✅ | — |
| `POST /periods/{yyyymm}/close`, `/reopen` | — | — | ✅ | — |
| `GET /reports/trial-balance` | ✅ | ✅ | ✅ | — |
| `GET/PUT/DELETE /admin/users/**` (tenant-scoped) | — | — | ✅ | — |
| `POST/GET/DELETE /admin/tenants/**` | — | — | — | ✅ |

PlatformAdmin does **not** get implicit access to tenant data. To operate
inside a tenant, a platform admin must also hold a tenant Admin role on
that tenant.

### 7.5 Error shapes

| Cause | Status | Problem URI |
|---|:-:|---|
| Missing/invalid/expired JWT | 401 | `/problems/auth/unauthenticated` |
| Valid JWT, no tenant claim and not platform admin | 403 | `/problems/auth/missing-tenant` |
| Valid JWT, tenant claim references unknown tenant | 403 | `/problems/auth/unknown-tenant` |
| Valid JWT + tenant but missing role for endpoint | 403 | `/problems/auth/insufficient-role` |

All four return `application/problem+json` with sanitized details (the
existing 64-char + control-char-strip sanitization from
`ValidationExceptionHandler` is reused — extracted to a shared utility).

## 8. Admin API + UI

### 8.1 Admin REST API

```
POST   /admin/tenants                        platform admin → create tenant
                                             body: { name }
                                             returns 201 + Location: /admin/tenants/{id}
GET    /admin/tenants                        platform admin → list all tenants
GET    /admin/tenants/{id}                   platform admin → fetch tenant
DELETE /admin/tenants/{id}                   platform admin → soft-delete

GET    /admin/users                          tenant admin → list (sub, role) for current tenant
PUT    /admin/users/{userSub}                tenant admin → grant/change role
                                             body: { role: "ADMIN"|"BOOKKEEPER"|"READ_ONLY" }
                                             200 (idempotent: same role on re-PUT is no-op)
DELETE /admin/users/{userSub}                tenant admin → revoke role
                                             returns 204
```

**Tenant deletion is soft only** — sets `deactivated_at`. The composite
foreign keys mean a hard `DELETE` would cascade through years of
accounting data. Operators who want to purge run a separate SQL script.

**Idempotency:** Granting the same role to the same user is a 200 with
the current state. Revoking a non-existent role is 404. Tenant names are
not unique — duplicates are allowed; use the UUID for identity.

**Cannot orphan yourself:** If a tenant Admin tries to demote themselves
while being the last Admin, return 400
`/problems/admin/cannot-orphan-self`. Avoids "tenant has no admins"
recovery footguns.

### 8.2 Thymeleaf+HTMX admin UI

Server-rendered Thymeleaf templates with HTMX 2.x for in-place row
mutations. No JS bundle, no Node.js, no `package.json`. HTMX served as a
vendored static asset (`src/main/resources/static/htmx.min.js`) — works
offline / in air-gapped deployments.

```
/admin/ui                  302 → /admin/ui/users
/admin/ui/login            triggers oauth2Login → IdP redirect
/admin/ui/users            list (user_sub, role, granted_at) for current tenant
                           per-row dropdown to change role (HTMX PUT, swap row)
                           "Add user" form (sub + role) at top (HTMX POST, prepend row)
                           "Remove" button (HTMX DELETE with confirm; row fades out)
/admin/ui/tenants          PLATFORM_ADMIN only — list all tenants + Create button
/admin/ui/tenants/{id}     PLATFORM_ADMIN only — tenant detail, deactivate button
/admin/ui/profile          read-only: shows current sub, current tenant, current role
```

Bootstrap CSS via the same static-asset path (no build step). Layout
template includes the CSRF token snippet for HTMX.

## 9. Bootstrap, defaults & configuration

### 9.1 application.yaml

```yaml
keystone:
  base-currency: USD                         # already exists from Slice 6
  default-tenant-id: "01902f9f-...-..."      # the UUID written by V6 migration
  security:
    issuer-uri: ${KEYSTONE_ISSUER_URI}       # required: IdP base URL
    audience: ${KEYSTONE_AUDIENCE}           # required: this app's audience
    tenant-claim: "https://keystone.embracejoy.co/tenant_id"
    bootstrap-platform-admin-sub: ${KEYSTONE_PLATFORM_ADMIN_SUB:}
  datasource:
    app:
      url: ${DATABASE_URL}
      username: keystone
      password: ${DATABASE_PASSWORD}
    platform:
      url: ${DATABASE_URL}
      username: keystone_platform
      password: ${KEYSTONE_PLATFORM_DB_PASSWORD}
```

### 9.2 Platform-admin bootstrap

An `ApplicationRunner` checks `bootstrap-platform-admin-sub` on startup:
- Empty: do nothing. Operators add platform admins via SQL.
- Set: `INSERT INTO platform_admins (user_sub) VALUES (?) ON CONFLICT DO NOTHING`.

Idempotent — restart-safe. Solves the chicken-and-egg problem: a fresh
deployment with `KEYSTONE_PLATFORM_ADMIN_SUB=auth0|abc123` lets that user
create the first tenant via the API or UI.

### 9.3 Postgres role provisioning

A new file ships in the repo: `docs/operations/setup-platform-role.sql`.

```sql
-- Run as a Postgres superuser, once per deployment.
CREATE USER keystone_platform WITH PASSWORD '<set in env>';
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO keystone_platform;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO keystone_platform;
ALTER USER keystone_platform BYPASSRLS;
```

`docker-compose.yml` runs this script automatically on container init for
local dev. Production operators run it once per deployment.

### 9.4 Configuration validation at startup

`@PostConstruct` on `KeystoneSecurityProperties` (a
`@ConfigurationProperties` record) fails fast with a clear message if:
- `issuer-uri` is missing/empty
- `audience` is missing/empty
- `default-tenant-id` doesn't reference a row in `tenants` (after Flyway
  has run)

A misconfigured deployment crashes at boot rather than confusing the
first request with a 500.

## 10. Testing strategy

### 10.1 Domain (pure JUnit, no Spring)

- `TenantTest`, `TenantIdTest` — record invariants.
- `RoleTest`, `PermissionTest` — enum coverage; `Role.permissions()` returns
  the right set.
- `TenantUserRoleTest` — invariants.
- Updates to `AccountTest`, `JournalEntryTest`, `PeriodTest` — every
  existing constructor test passes a `TenantId`. ~30 fixture updates.

### 10.2 Application (fakes, no Spring)

- `TenantServiceTest` — create/list/fetch/deactivate via fake repo.
- `UserRoleServiceTest` — grant/revoke; "can't orphan self"; idempotency.
- Existing `PostJournalEntryServiceTest`, `AccountServiceTest`,
  `PeriodServiceTest` — fake repos take `TenantId`.

### 10.3 Persistence (Testcontainers Postgres)

- `JpaTenantRepositoryIT` — CRUD + soft delete.
- `JpaTenantUserRoleRepositoryIT` — grant/find/revoke.
- `JpaPlatformAdminRepositoryIT` — bootstrap flow.
- Existing `AccountRepositoryAdapterIT`, `JpaJournalEntryRepositoryIT`,
  `PeriodRepositoryAdapterIT`, `TrialBalanceJdbcReadModelIT` — all gain a
  `shouldNotSeeOtherTenantsRows` test plus tenant-row seeding. Most touched
  tests in the slice.

### 10.4 RowLevelSecurityIT (centerpiece)

Runs against the app `DataSource` (no `BYPASSRLS`). Seeds two tenants A
and B with overlapping account codes:

- `SET LOCAL app.current_tenant = '<A>'` → `SELECT * FROM accounts` returns
  only A's rows.
- Switch to B → only B's rows.
- Set GUC to a bogus UUID → zero rows.
- Unset GUC → zero rows (the `true` arg to `current_setting` returns NULL).
- Try `INSERT` a row with the wrong `tenant_id` for the current GUC →
  rejected by `WITH CHECK`.

### 10.5 Web (`@WebMvcTest` with mock JWTs)

- `TenantControllerTest`, `UserRoleControllerTest` — happy + 403 + 400
  (orphan-self).
- `JwtTenantConverterTest` — claim extraction, unknown tenant → 403,
  missing claim + non-platform-admin → empty authorities.
- `SecurityExceptionHandlerTest` — all four ProblemDetail mappings.
- Existing controller tests gain `with(jwt().authorities(...))` setup.
  ~50 method updates.

### 10.6 UI (`@WebMvcTest` for Thymeleaf)

- `AdminUiControllerTest` — each page renders for an authenticated user;
  contains the expected DOM elements.
- HTMX paths: PUT/DELETE return the right fragment.
- CSRF enforcement: missing token → 403.

### 10.7 ArchUnit

- `TenancyArchTest` — every `@Repository` injects `TenantContext` or has
  `TenantId` as a method param.
- Every domain aggregate-root record has a `tenantId()` accessor.
- Every `@RestController` mapping is matched by one of the security
  filter chains.

### 10.8 Smoke (`ApplicationSmokeIT`)

- Bootstrap-via-env-var, create tenant via API, grant a user a role, post
  an entry, GET trial balance — all using minted JWTs (a `JwtTestSupport`
  helper signs tokens with a test key matching `issuer-uri` for the IT
  profile).
- Two-tenant isolation smoke: create A and B, post entries in each, query
  trial balance — only see your own.

### 10.9 Coverage gates

JaCoCo ≥ 85% line; PIT ≥ 60% mutation on `domain..` + `application..`.
Both must pass on the slice PR.

## 11. Migration plan (operational)

For a fresh deployment:
1. Run `setup-platform-role.sql` against Postgres.
2. Set `KEYSTONE_ISSUER_URI`, `KEYSTONE_AUDIENCE`,
   `KEYSTONE_PLATFORM_ADMIN_SUB`, and the two database password env vars.
3. `docker compose up` (or the equivalent). V6 migration runs; default
   tenant is created; platform admin is bootstrapped.
4. Platform admin gets a JWT from the IdP and calls `POST /admin/tenants`
   to create real tenants.
5. Platform admin grants themselves the Admin role on the default tenant
   (or any new tenant) via `PUT /admin/users/{their-sub}`.

For an existing standalone deployment (already running pre-Slice-5):
1. Run `setup-platform-role.sql`.
2. Set the new env vars.
3. Deploy the new build. V6 runs, backfilling existing rows to the
   default tenant. Their existing data is preserved.
4. Configure the IdP to issue tokens with
   `https://keystone.embracejoy.co/tenant_id = <default-tenant-id>` for
   their users. Now every request lands in the default tenant; everything
   keeps working.

## 12. Acceptance criteria

The slice is done when:

1. `./mvnw -B clean verify -Pmutation,openapi-gate` is green on the PR.
2. `POST /journal-entries` requires a JWT; without one, 401.
3. A JWT for tenant A cannot read or write tenant B's data — verified at
   both layers (app filter + RLS) by `RowLevelSecurityIT`.
4. A JWT with no tenant claim and no platform-admin grant gets 403
   `/problems/auth/missing-tenant` on every tenant-scoped endpoint.
5. A JWT with the wrong role (e.g., `READ_ONLY` calling
   `POST /journal-entries`) gets 403 `/problems/auth/insufficient-role`.
6. `POST /admin/tenants` requires platform-admin; tenant Admin gets 403.
7. The Thymeleaf admin UI loads, walks the operator through the OAuth2
   login redirect, and lets a tenant Admin grant a role.
8. `KEYSTONE_PLATFORM_ADMIN_SUB` env var bootstraps the first platform
   admin idempotently.
9. ADR-0015 (no URL versioning) ships with the slice.
10. `docs/openapi/openapi.yaml` regenerated with the new admin endpoints.
    Layer 4 of the openapi-gate flags the breaking changes (every existing
    endpoint now requires auth); the PR carries the
    `breaking-change-approved` label.
11. Issue #16 closes.
