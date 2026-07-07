package co.embracejoy.accounting.keystone.infrastructure.security;

import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.infrastructure.config.KeystoneSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * At startup, if {@code keystone.security.bootstrap-platform-admin-sub} is non-empty, idempotently
 * grants that IdP {@code sub} platform-admin authority (inserts into {@code platform_admins} with
 * {@code ON CONFLICT DO NOTHING} semantics via {@link PlatformAdminRepository#grant(String)}).
 *
 * <p>This is the seed for a fresh multi-tenant deployment: the environment sets {@code
 * KEYSTONE_PLATFORM_ADMIN_SUB} to a known user's IdP subject, keystone boots, that user can
 * immediately call {@code POST /admin/tenants} (Phase D) to create the first tenant.
 *
 * <p>Empty configuration is a no-op: nothing is inserted, nothing is logged. Restart-safe (repeat
 * grants collapse to the existing row).
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

  private final KeystoneSecurityProperties props;
  private final PlatformAdminRepository platformAdmins;

  public PlatformAdminBootstrap(
      KeystoneSecurityProperties props, PlatformAdminRepository platformAdmins) {
    this.props = props;
    this.platformAdmins = platformAdmins;
  }

  @Override
  public void run(ApplicationArguments args) {
    String sub = props.bootstrapPlatformAdminSub();
    if (sub == null || sub.isBlank()) {
      return;
    }
    platformAdmins.grant(sub);
    LOG.info("Bootstrapped platform admin: sub={}", sub);
  }
}
