package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import java.time.LocalDate;
import java.util.List;

/** Outbound representation of a persisted journal entry. */
public record JournalEntryResponse(
    String id, LocalDate occurredOn, String description, List<PostingResponse> postings) {

  public static JournalEntryResponse of(PersistedJournalEntry persisted) {
    List<PostingResponse> postings =
        persisted.entry().postings().stream().map(PostingResponse::of).toList();
    return new JournalEntryResponse(
        persisted.id().value().toString(),
        persisted.entry().occurredOn(),
        persisted.entry().description(),
        postings);
  }
}
