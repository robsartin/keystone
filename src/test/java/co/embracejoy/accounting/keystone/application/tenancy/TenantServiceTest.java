package co.embracejoy.accounting.keystone.application.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantError;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantService")
class TenantServiceTest {

  private static final Instant FIXED_TIME = Instant.parse("2026-05-13T10:00:00Z");

  private FakeTenantRepository repo;
  private FakeUuidSupplier uuids;
  private TenantService service;

  @BeforeEach
  void setup() {
    repo = new FakeTenantRepository();
    uuids = new FakeUuidSupplier();
    Clock clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    service = new TenantService(repo, clock, uuids);
  }

  @Test
  @DisplayName("create() saves a tenant with a fresh UUID, name, createdAt=now, no deactivatedAt")
  void shouldCreateTenant() {
    Result<Tenant, TenantError> r = service.create("Acme Corp");
    assertThat(r).isInstanceOf(Result.Success.class);
    Tenant t = ((Result.Success<Tenant, TenantError>) r).value();
    assertThat(t.name()).isEqualTo("Acme Corp");
    assertThat(t.createdAt()).isEqualTo(FIXED_TIME);
    assertThat(t.deactivatedAt()).isEmpty();
    assertThat(repo.byId).hasSize(1);
  }

  @Test
  @DisplayName("create() returns Failure(InvalidName) for blank name")
  void shouldReturnInvalidNameWhenBlank() {
    Result<Tenant, TenantError> r = service.create("   ");
    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Tenant, TenantError>) r).error())
        .isInstanceOf(TenantError.InvalidName.class);
  }

  @Test
  @DisplayName("findById returns the saved tenant")
  void shouldFindById() {
    Tenant created = ((Result.Success<Tenant, TenantError>) service.create("Acme")).value();
    Optional<Tenant> found = service.findById(created.id());
    assertThat(found).contains(created);
  }

  @Test
  @DisplayName("findAll returns all created tenants in insertion order")
  void shouldFindAll() {
    service.create("First");
    service.create("Second");
    List<Tenant> all = service.findAll();
    assertThat(all).extracting(Tenant::name).containsExactly("First", "Second");
  }

  @Test
  @DisplayName("deactivate sets deactivatedAt; subsequent finds show it deactivated")
  void shouldDeactivate() {
    Tenant created = ((Result.Success<Tenant, TenantError>) service.create("Acme")).value();
    Result<Tenant, TenantError> r = service.deactivate(created.id());
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(service.findById(created.id()).orElseThrow().isDeactivated()).isTrue();
  }

  @Test
  @DisplayName("deactivate returns Failure(NotFound) for unknown id")
  void shouldReturnNotFoundOnDeactivateUnknown() {
    Result<Tenant, TenantError> r = service.deactivate(new TenantId(UUID.randomUUID()));
    assertThat(((Result.Failure<Tenant, TenantError>) r).error())
        .isInstanceOf(TenantError.NotFound.class);
  }

  // ---- fakes ----

  private static final class FakeTenantRepository implements TenantRepository {
    final Map<TenantId, Tenant> byId = new HashMap<>();
    final List<Tenant> ordered = new ArrayList<>();

    @Override
    public Result<Tenant, TenantError> save(Tenant t) {
      byId.put(t.id(), t);
      ordered.add(t);
      return Result.success(t);
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Tenant> findAll() {
      return new ArrayList<>(ordered);
    }

    @Override
    public Result<Tenant, TenantError> deactivate(TenantId id) {
      Tenant existing = byId.get(id);
      if (existing == null) {
        return Result.failure(new TenantError.NotFound(id));
      }
      Tenant updated =
          new Tenant(existing.id(), existing.name(), existing.createdAt(), Optional.of(FIXED_TIME));
      byId.put(id, updated);
      ordered.replaceAll(t -> t.id().equals(id) ? updated : t);
      return Result.success(updated);
    }
  }

  private static final class FakeUuidSupplier implements Supplier<UUID> {
    int counter = 0;

    @Override
    public UUID get() {
      counter++;
      return UUID.fromString(String.format("01902f9f-0000-7000-8000-00000000000%d", counter));
    }
  }
}
