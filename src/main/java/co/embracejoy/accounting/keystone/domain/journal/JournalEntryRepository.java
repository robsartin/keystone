package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

  /**
   * Persist the given entry and return it with any storage-assigned identity attached. The keystone
   * phase returns the entry unchanged; Plan 2 introduces an identifier wrapper.
   */
  JournalEntry save(JournalEntry entry);

  /** Find an entry by its identifier (string for now; ULID later). */
  Optional<JournalEntry> findById(String id);
}
