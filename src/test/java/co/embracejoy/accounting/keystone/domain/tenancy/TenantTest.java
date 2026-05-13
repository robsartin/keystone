package co.embracejoy.accounting.keystone.domain.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tenant")
class TenantTest {

  private static final TenantId ID =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000"));
  private static final Instant CREATED = Instant.parse("2026-05-13T10:00:00Z");

  @Test
  @DisplayName("constructs with id, name, createdAt; deactivatedAt absent")
  void shouldConstructActiveTenant() {
    Tenant t = new Tenant(ID, "Acme Corp", CREATED, Optional.empty());
    assertEquals(ID, t.id());
    assertEquals("Acme Corp", t.name());
    assertEquals(CREATED, t.createdAt());
    assertTrue(t.isActive());
    assertFalse(t.isDeactivated());
  }

  @Test
  @DisplayName("isDeactivated() true when deactivatedAt is present")
  void shouldReportDeactivated() {
    Instant deactivated = Instant.parse("2026-06-01T12:00:00Z");
    Tenant t = new Tenant(ID, "Acme Corp", CREATED, Optional.of(deactivated));
    assertFalse(t.isActive());
    assertTrue(t.isDeactivated());
  }

  @Test
  @DisplayName("rejects null id")
  void shouldThrowWhenIdNull() {
    assertThrows(
        NullPointerException.class, () -> new Tenant(null, "Acme", CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null name")
  void shouldThrowWhenNameNull() {
    assertThrows(NullPointerException.class, () -> new Tenant(ID, null, CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects blank name")
  void shouldThrowWhenNameBlank() {
    assertThrows(
        IllegalArgumentException.class, () -> new Tenant(ID, "  ", CREATED, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null createdAt")
  void shouldThrowWhenCreatedAtNull() {
    assertThrows(NullPointerException.class, () -> new Tenant(ID, "Acme", null, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null deactivatedAt Optional")
  void shouldThrowWhenDeactivatedAtOptionalNull() {
    assertThrows(NullPointerException.class, () -> new Tenant(ID, "Acme", CREATED, null));
  }
}
