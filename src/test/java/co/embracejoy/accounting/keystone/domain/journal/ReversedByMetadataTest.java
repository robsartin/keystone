package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReversedByMetadata")
class ReversedByMetadataTest {

  private static final JournalEntryId REVERSAL =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000002"));
  private static final Instant REVERSED_AT = Instant.parse("2026-07-09T00:00:00Z");

  @Test
  @DisplayName("rejects null reversalId")
  void shouldThrowWhenReversalIdIsNull() {
    assertThatThrownBy(() -> new ReversedByMetadata(null, REVERSED_AT, "alice", "typo"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects null reversedAt")
  void shouldThrowWhenReversedAtIsNull() {
    assertThatThrownBy(() -> new ReversedByMetadata(REVERSAL, null, "alice", "typo"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects null reversedBy")
  void shouldThrowWhenReversedByIsNull() {
    assertThatThrownBy(() -> new ReversedByMetadata(REVERSAL, REVERSED_AT, null, "typo"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects null reason")
  void shouldThrowWhenReasonIsNull() {
    assertThatThrownBy(() -> new ReversedByMetadata(REVERSAL, REVERSED_AT, "alice", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("exposes all four components as constructed")
  void shouldExposeComponentsWhenConstructed() {
    ReversedByMetadata metadata = new ReversedByMetadata(REVERSAL, REVERSED_AT, "alice", "typo");
    assertThat(metadata.reversalId()).isEqualTo(REVERSAL);
    assertThat(metadata.reversedAt()).isEqualTo(REVERSED_AT);
    assertThat(metadata.reversedBy()).isEqualTo("alice");
    assertThat(metadata.reason()).isEqualTo("typo");
  }
}
