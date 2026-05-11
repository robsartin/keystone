package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

  /** Persist the given entry; the adapter assigns a {@link JournalEntryId}. */
  PersistedJournalEntry save(JournalEntry entry);

  /** Find a persisted entry by id, or {@code Optional.empty()}. */
  Optional<PersistedJournalEntry> findById(JournalEntryId id);
}
