package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

  /**
   * Persist the given entry; the adapter assigns a {@link JournalEntryId}.
   *
   * @param actor the identity (JWT sub) posting the entry, stamped onto {@code posted_by}
   */
  PersistedJournalEntry save(JournalEntry entry, String actor);

  /** Find a persisted entry by id for the given tenant, or {@code Optional.empty()}. */
  Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id);

  /**
   * The set of distinct YearMonths that have at least one persisted journal entry for the given
   * tenant. Used by PeriodService to compute "earliest open period with postings" for sequential
   * close.
   */
  java.util.Set<java.time.YearMonth> distinctOccurredMonths(TenantId tenantId);

  /**
   * Persist {@code reversal} as a reversal of an existing entry, writing the {@code reverses_id} +
   * {@code reversal_reason} columns from {@code metadata}.
   *
   * @param actor the identity (JWT sub) posting the reversal, stamped onto {@code posted_by}
   */
  PersistedJournalEntry saveReversal(
      JournalEntry reversal, ReversalMetadata metadata, String actor);

  /** Whether a reversal of {@code originalId} already exists for the given tenant. */
  boolean existsReversalOf(TenantId tenantId, JournalEntryId originalId);
}
