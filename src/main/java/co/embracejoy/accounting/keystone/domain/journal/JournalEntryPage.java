package co.embracejoy.accounting.keystone.domain.journal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of query results plus an optional cursor to the next page. */
public record JournalEntryPage(
    List<PersistedJournalEntry> items, Optional<JournalEntryId> nextCursor) {

  public JournalEntryPage {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(nextCursor, "nextCursor");
    items = List.copyOf(items);
  }
}
