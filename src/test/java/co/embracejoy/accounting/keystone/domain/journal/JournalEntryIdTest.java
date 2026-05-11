package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntryId")
class JournalEntryIdTest {

  @Test
  @DisplayName("rejects null UUID")
  void shouldThrowWhenValueIsNull() {
    assertThrows(NullPointerException.class, () -> new JournalEntryId(null));
  }

  @Test
  @DisplayName("equality is value-based on UUID")
  void shouldBeEqualWhenSameUuid() {
    UUID u = UUID.randomUUID();
    assertEquals(new JournalEntryId(u), new JournalEntryId(u));
  }

  @Test
  @DisplayName("different UUIDs produce different ids")
  void shouldDifferWhenDifferentUuids() {
    assertNotEquals(new JournalEntryId(UUID.randomUUID()), new JournalEntryId(UUID.randomUUID()));
  }
}
