package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy.TenantRepositoryAdapter;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@DisplayName("TenantUserRoleRepositoryAdapter (integration)")
class TenantUserRoleRepositoryAdapterIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired TenantUserRoleRepositoryAdapter adapter;
  @Autowired TenantRepositoryAdapter tenantRepo;
  @Autowired TenantContext tenantContext;

  // Use the well-known default tenant from V6, plus a second tenant for cross-tenant tests.
  private static final TenantId TENANT_A = Tenants.DEFAULT_TENANT_ID;

  // Unique per test-method instance — avoids cross-test row contamination.
  private final TenantId tenantB = new TenantId(UUID.randomUUID());

  // Unique tag per test-method instance — isolates TENANT_A rows across tests.
  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeEach
  void setupContext() {
    // Tenant B is created in tenants table (not RLS-protected, so no GUC needed here).
    tenantRepo.save(new Tenant(tenantB, "Tenant B " + tag, Instant.now(), Optional.empty()));
    // Default to operating in TENANT_A; tests that need tenantB switch context explicitly.
    tenantContext.set(TENANT_A);
  }

  /** Build a unique user sub scoped to this test instance using the per-instance tag. */
  private String sub(String name) {
    return "auth0|" + name + "-" + tag;
  }

  private TenantUserRole role(TenantId tenant, String sub, Role role) {
    return new TenantUserRole(tenant, sub, role, Instant.now(), "auth0|root");
  }

  @Test
  @DisplayName("grant inserts a new role assignment within the current tenant context")
  void shouldGrantNewRole() {
    String alice = sub("alice");
    TenantUserRole granted = adapter.grant(role(TENANT_A, alice, Role.BOOKKEEPER));
    assertThat(granted.role()).isEqualTo(Role.BOOKKEEPER);

    Optional<TenantUserRole> found = adapter.findRole(TENANT_A, alice);
    assertThat(found).isPresent();
    assertThat(found.get().role()).isEqualTo(Role.BOOKKEEPER);
  }

  @Test
  @DisplayName("grant is idempotent — same key + same role overwrites in place")
  void shouldUpsertOnSameKey() {
    String alice = sub("alice");
    adapter.grant(role(TENANT_A, alice, Role.BOOKKEEPER));
    adapter.grant(role(TENANT_A, alice, Role.ADMIN));
    Optional<TenantUserRole> after = adapter.findRole(TENANT_A, alice);
    assertThat(after).isPresent();
    assertThat(after.get().role()).isEqualTo(Role.ADMIN);
  }

  @Test
  @DisplayName("findByTenant returns rows for the current tenant ordered by grantedAt ASC")
  void shouldListByTenant() {
    String alice = sub("alice");
    String bob = sub("bob");
    adapter.grant(role(TENANT_A, alice, Role.ADMIN));
    adapter.grant(role(TENANT_A, bob, Role.READ_ONLY));
    List<TenantUserRole> rows = adapter.findByTenant(TENANT_A);
    // Filter to rows inserted by this test (other tests may have their own tagged rows).
    List<String> subs =
        rows.stream().map(TenantUserRole::userSub).filter(s -> s.endsWith(tag)).toList();
    assertThat(subs).containsExactly(alice, bob);
  }

  @Test
  @DisplayName("revoke removes the row")
  void shouldRevoke() {
    String alice = sub("alice");
    adapter.grant(role(TENANT_A, alice, Role.ADMIN));
    boolean removed = adapter.revoke(TENANT_A, alice);
    assertThat(removed).isTrue();
    assertThat(adapter.findRole(TENANT_A, alice)).isEmpty();
  }

  @Test
  @DisplayName("revoke returns false when no row exists")
  void shouldReturnFalseOnRevokeMissing() {
    boolean removed = adapter.revoke(TENANT_A, sub("ghost"));
    assertThat(removed).isFalse();
  }

  @Test
  @DisplayName("countAdmins returns only ADMIN rows for the current tenant")
  void shouldCountAdminsOnly() {
    adapter.grant(role(TENANT_A, sub("alice"), Role.ADMIN));
    adapter.grant(role(TENANT_A, sub("bob"), Role.BOOKKEEPER));
    adapter.grant(role(TENANT_A, sub("carol"), Role.ADMIN));
    // Count only the admins added by this test (filter by tag in sub).
    long admins =
        adapter.findByTenant(TENANT_A).stream()
            .filter(r -> r.userSub().endsWith(tag) && r.role() == Role.ADMIN)
            .count();
    assertThat(admins).isEqualTo(2);
    // Also verify the adapter method itself returns at least 2 (it counts all ADMIN in TENANT_A).
    assertThat(adapter.countAdmins(TENANT_A)).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("RLS isolation: switching the tenant context changes the visible rows")
  void shouldIsolateAcrossTenantsViaRls() {
    String alice = sub("alice");
    String bob = sub("bob");
    // Seed Alice in TENANT_A
    adapter.grant(role(TENANT_A, alice, Role.ADMIN));
    // Switch context to tenantB and seed Bob there
    tenantContext.set(tenantB);
    adapter.grant(role(tenantB, bob, Role.BOOKKEEPER));
    // Still in tenantB context: should NOT see Alice from TENANT_A
    assertThat(adapter.findRole(tenantB, alice)).isEmpty();
    assertThat(adapter.findByTenant(tenantB))
        .extracting(TenantUserRole::userSub)
        .containsExactly(bob);
    // Switch back to TENANT_A
    tenantContext.set(TENANT_A);
    assertThat(adapter.findRole(TENANT_A, alice)).isPresent();
    assertThat(adapter.findRole(TENANT_A, bob)).isEmpty();
  }
}
