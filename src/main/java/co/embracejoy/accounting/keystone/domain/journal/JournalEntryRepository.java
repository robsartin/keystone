package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

  /** Persist the given entry; the adapter assigns a {@link JournalEntryId}. */
  PersistedJournalEntry save(JournalEntry entry);

  /** Find a persisted entry by id for the given tenant, or {@code Optional.empty()}. */
  Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id);

  /**
   * The set of distinct YearMonths that have at least one persisted journal entry for the given
   * tenant. Used by PeriodService to compute "earliest open period with postings" for sequential
   * close.
   */
  java.util.Set<java.time.YearMonth> distinctOccurredMonths(TenantId tenantId);
}
