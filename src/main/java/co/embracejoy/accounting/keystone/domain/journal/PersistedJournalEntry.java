package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link JournalEntry} that has been persisted, paired with its storage-assigned id and any
 * reversal-graph metadata the read model has surfaced.
 *
 * <p>{@code reverses} is populated when this entry is itself a reversal (from the row-stored
 * columns). {@code reversedBy} is populated only by the read model via LEFT JOIN — writing the
 * entry does not know whether it will later be reversed, so {@code reversedBy} is always {@code
 * Optional.empty()} at save time.
 */
public record PersistedJournalEntry(
    JournalEntryId id,
    JournalEntry entry,
    Optional<ReversalMetadata> reverses,
    Optional<ReversedByMetadata> reversedBy) {

  public PersistedJournalEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(reverses, "reverses");
    Objects.requireNonNull(reversedBy, "reversedBy");
  }

  /** Backwards-compatible constructor for callers that don't know about the reversal graph. */
  public PersistedJournalEntry(JournalEntryId id, JournalEntry entry) {
    this(id, entry, Optional.empty(), Optional.empty());
  }
}
