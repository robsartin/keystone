package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identifier for a {@link JournalEntry}.
 *
 * <p>Wraps a {@link UUID} (UUID v7 in production via the infrastructure generator) so it cannot be
 * confused with other id types added in future slices.
 */
public record JournalEntryId(UUID value) {

  public JournalEntryId {
    Objects.requireNonNull(value, "value");
  }
}
