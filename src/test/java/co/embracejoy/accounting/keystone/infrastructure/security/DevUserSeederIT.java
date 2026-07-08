package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link DevUserSeeder} idempotently seeds the 3 demo users on startup.
 *
 * <p>The {@code @Container @ServiceConnection} Postgres mirrors {@link
 * EmbeddedAuthorizationServerConfigIT} — see its javadoc for why it's needed.
 *
 * <p>No longer excludes {@code OAuth2ClientAutoConfiguration} (T2's original workaround for eager
 * OIDC discovery): T3's {@code application.yaml} now configures explicit OAuth2 provider endpoint
 * URIs instead of an {@code issuer-uri}, so building the {@code ClientRegistrationRepository} never
 * triggers a discovery round-trip. Excluding the autoconfiguration here would instead break context
 * refresh, since {@code UiSecurityConfig} (active on the {@code test} profile) requires a {@code
 * ClientRegistrationRepository} bean.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("DevUserSeeder")
class DevUserSeederIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("seeds sas|platform into platform_admins")
  void shouldSeedPlatformAdmin() {
    Long count =
        jdbc.sql("SELECT count(*) FROM platform_admins WHERE user_sub = 'sas|platform'")
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("seeds three rows into tenant_user_roles")
  void shouldSeedTenantUserRoles() {
    Long count =
        jdbc.sql("SELECT count(*) FROM tenant_user_roles WHERE user_sub LIKE 'sas|%'")
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(3L);
  }
}
