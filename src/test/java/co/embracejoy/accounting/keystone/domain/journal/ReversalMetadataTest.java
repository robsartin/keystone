package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReversalMetadata")
class ReversalMetadataTest {

  private static final JournalEntryId ORIGINAL =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));

  @Test
  @DisplayName("rejects null reversesId")
  void shouldThrowWhenReversesIdIsNull() {
    assertThatThrownBy(() -> new ReversalMetadata(null, "typo"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects null reason")
  void shouldThrowWhenReasonIsNull() {
    assertThatThrownBy(() -> new ReversalMetadata(ORIGINAL, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("rejects blank reason")
  void shouldThrowWhenReasonIsBlank() {
    assertThatThrownBy(() -> new ReversalMetadata(ORIGINAL, "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("exposes reversesId and reason as record components")
  void shouldExposeComponentsWhenConstructed() {
    ReversalMetadata metadata = new ReversalMetadata(ORIGINAL, "wrong account");
    assertThat(metadata.reversesId()).isEqualTo(ORIGINAL);
    assertThat(metadata.reason()).isEqualTo("wrong account");
  }
}
