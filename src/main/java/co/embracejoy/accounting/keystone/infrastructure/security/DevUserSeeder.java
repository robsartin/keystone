package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotently seeds three demo users on dev/test startup. The {@code platform_admins} row is
 * inserted via {@link PlatformAdminRepository#grant(String)} (no {@link TenantContext} needed). The
 * three {@code tenant_user_roles} rows are inserted via raw JDBC because {@code
 * TenantUserRoleRepositoryAdapter} calls {@code tenantContext.require()} on every method —
 * unavailable during startup — and because the table is RLS-protected, requiring {@code SET LOCAL
 * app.current_tenant} inside an explicit transaction.
 *
 * <p>The three demo users match {@link EmbeddedAuthorizationServerConfig}'s in-memory SAS accounts:
 * {@code sas|platform} (also a platform admin), {@code sas|admin}, and {@code sas|bookkeeper} — all
 * granted roles in {@link Tenants#DEFAULT_TENANT_UUID}.
 *
 * <p>{@code tenant_user_roles} is RLS-protected (see V6 migration), so its insert runs inside an
 * explicit transaction that first sets the {@code app.current_tenant} GUC via {@code SET LOCAL} —
 * the same mechanism {@code RlsTransactionInterceptor} uses for request-scoped writes. {@code
 * platform_admins} is intentionally not RLS-protected, so no GUC is needed there.
 *
 * <p>Timestamps for {@code tenant_user_roles} use the database's own {@code now()} rather than a
 * bound {@code java.time.Instant} parameter — matching {@code ApplicationSmokeIT}'s raw-JDBC {@code
 * tenant_user_roles} seeding — to sidestep pgjdbc's lack of a default SQL-type mapping for {@code
 * Instant} on {@code setObject}.
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(
    name = "keystone.dev.seed-users",
    havingValue = "true",
    matchIfMissing = true)
public class DevUserSeeder implements ApplicationRunner {

  private static final Map<String, String> SEED_ROLES =
      Map.of(
          "sas|platform", "ADMIN",
          "sas|admin", "ADMIN",
          "sas|bookkeeper", "BOOKKEEPER");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactionTemplate;
  private final PlatformAdminRepository platformAdmins;

  public DevUserSeeder(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      PlatformAdminRepository platformAdmins) {
    this.jdbc = jdbc;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.platformAdmins = platformAdmins;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedPlatformAdmin();
    transactionTemplate.executeWithoutResult(status -> seedTenantUserRoles());
  }

  private void seedPlatformAdmin() {
    platformAdmins.grant("sas|platform");
  }

  private void seedTenantUserRoles() {
    jdbc.sql("SET LOCAL app.current_tenant = '" + Tenants.DEFAULT_TENANT_UUID + "'").update();
    SEED_ROLES.forEach(this::insertTenantUserRole);
  }

  private void insertTenantUserRole(String userSub, String role) {
    jdbc.sql(
            "INSERT INTO tenant_user_roles (tenant_id, user_sub, role, granted_at, granted_by) "
                + "VALUES (?, ?, ?, now(), 'system') ON CONFLICT DO NOTHING")
        .param(Tenants.DEFAULT_TENANT_UUID)
        .param(userSub)
        .param(role)
        .update();
  }
}
