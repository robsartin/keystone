package co.embracejoy.accounting.keystone.domain.security;

import java.util.Set;

/**
 * Three tenant-scoped roles, each with a fixed bundle of {@link Permission}s. Per the spec (§7.4
 * permission matrix):
 *
 * <ul>
 *   <li>{@code READ_ONLY} — auditors / executives. Read books only.
 *   <li>{@code BOOKKEEPER} — daily entries + create accounts; cannot deactivate accounts or close
 *       periods.
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
