package co.embracejoy.accounting.keystone.domain.journal;

import java.time.Instant;
import java.util.Objects;

/**
 * Derived read-side view populated by the JdbcClient read model when this entry has been reversed.
 *
 * <p>Never stored on the row itself; populated via LEFT JOIN journal_entries r ON r.reverses_id =
 * e.id.
 */
public record ReversedByMetadata(
    JournalEntryId reversalId, Instant reversedAt, String reversedBy, String reason) {

  public ReversedByMetadata {
    Objects.requireNonNull(reversalId, "reversalId");
    Objects.requireNonNull(reversedAt, "reversedAt");
    Objects.requireNonNull(reversedBy, "reversedBy");
    Objects.requireNonNull(reason, "reason");
  }
}
