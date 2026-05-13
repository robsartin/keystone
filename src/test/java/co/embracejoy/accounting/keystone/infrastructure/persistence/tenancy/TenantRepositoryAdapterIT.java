package co.embracejoy.accounting.keystone.infrastructure.persistence.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
@DisplayName("TenantRepositoryAdapter (integration)")
class TenantRepositoryAdapterIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired TenantRepositoryAdapter repository;

  private Tenant freshTenant(String name) {
    return new Tenant(new TenantId(UUID.randomUUID()), name, Instant.now(), Optional.empty());
  }

  @Test
  @DisplayName("save persists a new tenant and findById reads it back")
  void shouldRoundTripWhenSavingThenReading() {
    Tenant t = freshTenant("Acme");
    Result<Tenant, TenantError> r = repository.save(t);

    assertThat(r).isInstanceOf(Result.Success.class);
    Optional<Tenant> found = repository.findById(t.id());
    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(t.id());
    assertThat(found.get().name()).isEqualTo("Acme");
    assertThat(found.get().isActive()).isTrue();
  }

  @Test
  @DisplayName("findById returns Optional.empty for unknown id")
  void shouldReturnEmptyForUnknownId() {
    Optional<Tenant> found = repository.findById(new TenantId(UUID.randomUUID()));
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("findAll returns the default tenant + any others, ordered by createdAt ASC")
  void shouldListAllOrderedByCreatedAt() {
    repository.save(freshTenant("Acme"));
    repository.save(freshTenant("Beta"));

    List<Tenant> all = repository.findAll();
    // Default tenant from V6 was inserted earlier (older createdAt) — should appear first.
    assertThat(all).extracting(Tenant::name).startsWith("Default Tenant");
    assertThat(all).extracting(Tenant::name).contains("Acme", "Beta");
  }

  @Test
  @DisplayName("deactivate sets deactivatedAt; subsequent finds show it deactivated")
  void shouldDeactivateExistingTenant() {
    Tenant t = freshTenant("Acme");
    repository.save(t);

    Result<Tenant, TenantError> r = repository.deactivate(t.id());

    assertThat(r).isInstanceOf(Result.Success.class);
    Tenant fetched = repository.findById(t.id()).orElseThrow();
    assertThat(fetched.isDeactivated()).isTrue();
    assertThat(fetched.deactivatedAt()).isPresent();
  }

  @Test
  @DisplayName("deactivate returns Failure(NotFound) for unknown id")
  void shouldReturnNotFoundOnDeactivateUnknown() {
    Result<Tenant, TenantError> r = repository.deactivate(new TenantId(UUID.randomUUID()));
    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Tenant, TenantError>) r).error())
        .isInstanceOf(TenantError.NotFound.class);
  }
}
