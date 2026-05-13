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
    Result<TenantUserRole, SecurityError> r = service.grant(TENANT, ALICE, Role.BOOKKEEPER, ALICE);
    assertThat(((Result.Failure<TenantUserRole, SecurityError>) r).error())
        .isInstanceOf(SecurityError.CannotOrphanSelf.class);
  }

  @Test
  @DisplayName("can demote yourself when another Admin still exists")
  void shouldAllowSelfDemoteWhenOtherAdminExists() {
    service.grant(TENANT, ALICE, Role.ADMIN, BOB);
    service.grant(TENANT, BOB, Role.ADMIN, ALICE);
    Result<TenantUserRole, SecurityError> r = service.grant(TENANT, ALICE, Role.BOOKKEEPER, ALICE);
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
        if (e.getKey().tenantId().equals(tenantId)) {
          out.add(e.getValue());
        }
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
