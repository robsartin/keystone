package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves {@link DevUserSeeder} idempotently seeds the 3 demo users on startup.
 *
 * <p>{@code spring.autoconfigure.exclude} + the {@code @Container @ServiceConnection} Postgres
 * mirror {@link EmbeddedAuthorizationServerConfigIT} — see its javadoc for why both are needed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration")
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
