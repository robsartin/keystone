package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/**
 * Read-side port for journal-entry browsing. Implemented in infrastructure with a JdbcClient
 * adapter, per the {@code TrialBalanceReadModel} precedent from Slice 4.
 */
public interface JournalEntryReadModel {

  JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query);

  Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id);
}
