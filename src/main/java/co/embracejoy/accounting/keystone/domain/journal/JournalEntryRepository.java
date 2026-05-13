package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

  /** Persist the given entry; the adapter assigns a {@link JournalEntryId}. */
  PersistedJournalEntry save(JournalEntry entry);

  /** Find a persisted entry by id, or {@code Optional.empty()}. */
  Optional<PersistedJournalEntry> findById(JournalEntryId id);

  /**
   * The set of distinct YearMonths that have at least one persisted journal entry. Used by
   * PeriodService to compute "earliest open period with postings" for sequential close.
   */
  java.util.Set<java.time.YearMonth> distinctOccurredMonths();
}
