package co.embracejoy.accounting.keystone.application.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryQuery;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryReadModel;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("JournalEntryQueryService")
class JournalEntryQueryServiceTest {

  private final JournalEntryReadModel readModel = Mockito.mock(JournalEntryReadModel.class);
  private final JournalEntryQueryService service = new JournalEntryQueryService(readModel);

  @Test
  @DisplayName("findMany delegates to read model with the same tenant + query")
  void shouldDelegateFindMany() {
    JournalEntryQuery query =
        new JournalEntryQuery(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50);
    JournalEntryPage expected = new JournalEntryPage(List.of(), Optional.empty());
    Mockito.when(readModel.findMany(Mockito.any(), Mockito.eq(query))).thenReturn(expected);

    JournalEntryPage got = service.findMany(Tenants.DEFAULT_TENANT_ID, query);

    assertThat(got).isSameAs(expected);
  }

  @Test
  @DisplayName("findById delegates to read model")
  void shouldDelegateFindById() {
    JournalEntryId id = new JournalEntryId(UUID.randomUUID());
    Optional<PersistedJournalEntry> expected = Optional.empty();
    Mockito.when(readModel.findById(Mockito.any(), Mockito.eq(id))).thenReturn(expected);

    assertThat(service.findById(Tenants.DEFAULT_TENANT_ID, id)).isSameAs(expected);
  }
}
