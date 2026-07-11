package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import java.util.List;

/** Outbound representation of a page of journal entries plus an optional next-page cursor. */
public record ListJournalEntriesResponse(List<JournalEntryResponse> items, String nextCursor) {

  public static ListJournalEntriesResponse of(JournalEntryPage page) {
    return new ListJournalEntriesResponse(
        page.items().stream().map(JournalEntryResponse::of).toList(),
        page.nextCursor().map(id -> id.value().toString()).orElse(null));
  }
}
