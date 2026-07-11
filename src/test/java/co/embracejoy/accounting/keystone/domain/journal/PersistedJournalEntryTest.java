package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PersistedJournalEntry")
class PersistedJournalEntryTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  private static JournalEntry validEntry() {
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(
            TENANT,
            LocalDate.parse("2026-05-10"),
            "x",
            List.of(
                new Posting(
                    new AccountCode("1000"), Side.DEBIT, new Money(1L, USD), new Money(1L, USD)),
                new Posting(
                    new AccountCode("3000"), Side.CREDIT, new Money(1L, USD), new Money(1L, USD))));
    return ((Result.Success<JournalEntry, JournalError>) r).value();
  }

  @Test
  @DisplayName("rejects null id")
  void shouldThrowWhenIdIsNull() {
    assertThrows(NullPointerException.class, () -> new PersistedJournalEntry(null, validEntry()));
  }

  @Test
  @DisplayName("rejects null entry")
  void shouldThrowWhenEntryIsNull() {
    assertThrows(
        NullPointerException.class,
        () -> new PersistedJournalEntry(new JournalEntryId(UUID.randomUUID()), null));
  }

  @Test
  @DisplayName("exposes id and entry as record components")
  void shouldExposeIdAndEntryWhenConstructed() {
    JournalEntry entry = validEntry();
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    PersistedJournalEntry p = new PersistedJournalEntry(id, entry);
    assertSame(id, p.id());
    assertSame(entry, p.entry());
  }

  @Test
  @DisplayName("equality is value-based")
  void shouldBeEqualWhenIdAndEntryMatch() {
    JournalEntry entry = validEntry();
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    assertEquals(new PersistedJournalEntry(id, entry), new PersistedJournalEntry(id, entry));
  }

  @Test
  @DisplayName("two-arg constructor defaults reversal metadata to empty")
  void shouldDefaultReversalMetadataToEmpty() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    JournalEntry entry = validEntry();
    PersistedJournalEntry p = new PersistedJournalEntry(id, entry);
    assertEquals(Optional.empty(), p.reverses());
    assertEquals(Optional.empty(), p.reversedBy());
  }

  @Test
  @DisplayName("four-arg constructor exposes reverses and reversedBy")
  void shouldExposeReversalMetadataWhenConstructedWithFourArgs() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    JournalEntry entry = validEntry();
    JournalEntryId originalId = new JournalEntryId(UUID.randomUUID());
    ReversalMetadata reverses = new ReversalMetadata(originalId, "typo");
    JournalEntryId reversalId = new JournalEntryId(UUID.randomUUID());
    ReversedByMetadata reversedBy =
        new ReversedByMetadata(reversalId, Instant.parse("2026-07-09T00:00:00Z"), "alice", "typo");

    PersistedJournalEntry p =
        new PersistedJournalEntry(id, entry, Optional.of(reverses), Optional.of(reversedBy));

    assertEquals(Optional.of(reverses), p.reverses());
    assertEquals(Optional.of(reversedBy), p.reversedBy());
  }

  @Test
  @DisplayName("rejects null reverses")
  void shouldThrowWhenReversesIsNull() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    JournalEntry entry = validEntry();
    assertThrows(
        NullPointerException.class,
        () -> new PersistedJournalEntry(id, entry, null, Optional.empty()));
  }

  @Test
  @DisplayName("rejects null reversedBy")
  void shouldThrowWhenReversedByIsNull() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    JournalEntry entry = validEntry();
    assertThrows(
        NullPointerException.class,
        () -> new PersistedJournalEntry(id, entry, Optional.empty(), null));
  }
}
