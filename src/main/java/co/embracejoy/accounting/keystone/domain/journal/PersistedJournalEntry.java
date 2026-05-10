package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;

/**
 * A {@link JournalEntry} that has been persisted, paired with its storage-assigned {@link
 * JournalEntryId}.
 */
public record PersistedJournalEntry(JournalEntryId id, JournalEntry entry) {

  public PersistedJournalEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(entry, "entry");
  }
}
