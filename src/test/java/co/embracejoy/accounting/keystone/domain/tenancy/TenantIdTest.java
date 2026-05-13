package co.embracejoy.accounting.keystone.domain.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantId")
class TenantIdTest {

  @Test
  @DisplayName("wraps a non-null UUID")
  void shouldWrapUuid() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    TenantId tenantId = new TenantId(uuid);
    assertEquals(uuid, tenantId.value());
  }

  @Test
  @DisplayName("rejects null UUID")
  void shouldThrowWhenUuidIsNull() {
    assertThrows(NullPointerException.class, () -> new TenantId(null));
  }

  @Test
  @DisplayName("equal when wrapping the same UUID")
  void shouldEqualWhenSameUuid() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    assertEquals(new TenantId(uuid), new TenantId(uuid));
  }

  @Test
  @DisplayName("not equal when wrapping different UUIDs")
  void shouldNotEqualWhenDifferentUuid() {
    assertNotEquals(
        new TenantId(UUID.fromString("01902f9f-0000-7000-8000-000000000000")),
        new TenantId(UUID.fromString("01902fa0-0000-7000-8000-000000000000")));
  }

  @Test
  @DisplayName("toString includes the UUID value (debuggability)")
  void shouldIncludeUuidInToString() {
    UUID uuid = UUID.fromString("01902f9f-0000-7000-8000-000000000000");
    assertEquals("TenantId[value=" + uuid + "]", new TenantId(uuid).toString());
  }
}
