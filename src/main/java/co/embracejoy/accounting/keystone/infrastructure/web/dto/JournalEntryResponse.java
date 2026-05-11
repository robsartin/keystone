package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import java.time.LocalDate;
import java.util.List;

/** Outbound representation of a persisted journal entry. */
public record JournalEntryResponse(
    String id,
    LocalDate occurredOn,
    String description,
    String currency,
    List<PostingResponse> postings) {

  public static JournalEntryResponse of(PersistedJournalEntry persisted) {
    List<PostingResponse> postings =
        persisted.entry().postings().stream().map(JournalEntryResponse::toResponse).toList();
    return new JournalEntryResponse(
        persisted.id().value().toString(),
        persisted.entry().occurredOn(),
        persisted.entry().description(),
        persisted.entry().currency().getCurrencyCode(),
        postings);
  }

  private static PostingResponse toResponse(Posting p) {
    return new PostingResponse(p.account().value(), p.side().name(), p.amount().minorUnits());
  }
}
