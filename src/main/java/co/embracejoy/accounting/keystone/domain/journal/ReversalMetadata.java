package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;

/**
 * Metadata attached to a persisted reversal entry — the id of what it reverses + the
 * operator-supplied reason.
 */
public record ReversalMetadata(JournalEntryId reversesId, String reason) {

  public ReversalMetadata {
    Objects.requireNonNull(reversesId, "reversesId");
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }
}
