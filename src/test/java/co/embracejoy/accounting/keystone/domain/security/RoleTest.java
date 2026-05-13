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
