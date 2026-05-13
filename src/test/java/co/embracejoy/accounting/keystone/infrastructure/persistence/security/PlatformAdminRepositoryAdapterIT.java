package co.embracejoy.accounting.keystone.infrastructure.persistence.security;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.security.PlatformAdmin;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@Transactional
@DisplayName("PlatformAdminRepositoryAdapter (integration)")
class PlatformAdminRepositoryAdapterIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired PlatformAdminRepositoryAdapter repository;

  @Test
  @DisplayName("grant inserts a new platform admin and findBySub reads it back")
  void shouldGrantAndFindBack() {
    PlatformAdmin granted = repository.grant("auth0|root");
    assertThat(granted.userSub()).isEqualTo("auth0|root");
    assertThat(granted.grantedAt()).isNotNull();

    Optional<PlatformAdmin> found = repository.findBySub("auth0|root");
    assertThat(found).isPresent();
    assertThat(found.get().userSub()).isEqualTo("auth0|root");
  }

  @Test
  @DisplayName("grant is idempotent — re-grant returns the existing row unchanged")
  void shouldBeIdempotent() {
    PlatformAdmin first = repository.grant("auth0|root");
    PlatformAdmin second = repository.grant("auth0|root");
    assertThat(second.grantedAt()).isEqualTo(first.grantedAt());
  }

  @Test
  @DisplayName("findBySub returns Optional.empty for unknown sub")
  void shouldReturnEmptyForUnknownSub() {
    Optional<PlatformAdmin> found = repository.findBySub("auth0|ghost");
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("exists returns true after grant, false otherwise")
  void shouldReportExistence() {
    assertThat(repository.exists("auth0|alice")).isFalse();
    repository.grant("auth0|alice");
    assertThat(repository.exists("auth0|alice")).isTrue();
  }

  @Test
  @DisplayName("findAll lists all granted admins ordered by grantedAt ASC")
  void shouldListAllOrdered() {
    repository.grant("auth0|first");
    repository.grant("auth0|second");
    List<PlatformAdmin> all = repository.findAll();
    // Both subs should appear; first → second order.
    assertThat(all).extracting(PlatformAdmin::userSub).contains("auth0|first", "auth0|second");
    int firstIdx = indexOfSub(all, "auth0|first");
    int secondIdx = indexOfSub(all, "auth0|second");
    assertThat(firstIdx).isLessThan(secondIdx);
  }

  private static int indexOfSub(List<PlatformAdmin> list, String sub) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).userSub().equals(sub)) {
        return i;
      }
    }
    return -1;
  }
}
