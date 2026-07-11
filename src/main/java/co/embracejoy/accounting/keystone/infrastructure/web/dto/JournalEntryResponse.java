package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.ReversedByMetadata;
import java.time.LocalDate;
import java.util.List;

/** Outbound representation of a persisted journal entry. */
public record JournalEntryResponse(
    String id,
    LocalDate occurredOn,
    String description,
    List<PostingResponse> postings,
    String reversesId,
    String reversalReason,
    String reversedById,
    String reversedAt,
    String reversedBy,
    String reversedReason) {

  public static JournalEntryResponse of(PersistedJournalEntry p) {
    return new JournalEntryResponse(
        p.id().value().toString(),
        p.entry().occurredOn(),
        p.entry().description(),
        p.entry().postings().stream().map(PostingResponse::of).toList(),
        p.reverses()
            .map(ReversalMetadata::reversesId)
            .map(id -> id.value().toString())
            .orElse(null),
        p.reverses().map(ReversalMetadata::reason).orElse(null),
        p.reversedBy()
            .map(ReversedByMetadata::reversalId)
            .map(id -> id.value().toString())
            .orElse(null),
        p.reversedBy().map(ReversedByMetadata::reversedAt).map(Object::toString).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reversedBy).orElse(null),
        p.reversedBy().map(ReversedByMetadata::reason).orElse(null));
  }
}
