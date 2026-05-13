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
