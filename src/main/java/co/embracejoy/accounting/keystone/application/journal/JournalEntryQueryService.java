package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryQuery;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryReadModel;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.Optional;

/**
 * Thin read-side application service. All logic lives in the read model — this exists purely to
 * keep the controller from reaching directly into the port (hexagonal layering + ArchUnit rule
 * WEB_DOES_NOT_DEPEND_ON_PERSISTENCE_ENTITIES).
 */
public class JournalEntryQueryService {

  private final JournalEntryReadModel readModel;

  public JournalEntryQueryService(JournalEntryReadModel readModel) {
    this.readModel = readModel;
  }

  public JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query) {
    return readModel.findMany(tenantId, query);
  }

  public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
    return readModel.findById(tenantId, id);
  }
}
