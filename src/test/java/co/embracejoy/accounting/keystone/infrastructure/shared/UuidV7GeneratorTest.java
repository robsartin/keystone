package co.embracejoy.accounting.keystone.infrastructure.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UuidV7Generator")
class UuidV7GeneratorTest {

  @Test
  @DisplayName("create() returns a UUID of version 7")
  void shouldReturnVersion7Uuid() {
    assertEquals(7, UuidV7Generator.create().version());
  }

  @Test
  @DisplayName("create() returns a UUID of RFC 4122 variant")
  void shouldReturnRfc4122Variant() {
    assertEquals(2, UuidV7Generator.create().variant());
  }

  @Test
  @DisplayName("repeated calls produce distinct UUIDs")
  void shouldProduceDistinctUuids() {
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      assertTrue(seen.add(UuidV7Generator.create()), "duplicate UUID generated");
    }
  }

  @Test
  @DisplayName("UUIDs created later have a non-decreasing timestamp prefix")
  void shouldHaveNonDecreasingTimestampWhenCreatedSequentially() throws InterruptedException {
    UUID a = UuidV7Generator.create();
    Thread.sleep(2);
    UUID b = UuidV7Generator.create();
    long tsA = a.getMostSignificantBits() >>> 16;
    long tsB = b.getMostSignificantBits() >>> 16;
    assertTrue(tsB >= tsA, "expected timestamp B (" + tsB + ") >= A (" + tsA + ")");
  }
}
