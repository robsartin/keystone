package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntryQuery")
class JournalEntryQueryTest {

  @Test
  @DisplayName("all filters default to empty and limit is preserved")
  void shouldConstructWithEmptyFilters() {
    JournalEntryQuery q =
        new JournalEntryQuery(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50);
    assertThat(q.limit()).isEqualTo(50);
    assertThat(q.from()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("limit below 1 throws IllegalArgumentException")
  void shouldThrowWhenLimitBelowOne() {
    assertThatThrownBy(
            () ->
                new JournalEntryQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }

  @Test
  @DisplayName("limit above 200 throws IllegalArgumentException")
  void shouldThrowWhenLimitAbove200() {
    assertThatThrownBy(
            () ->
                new JournalEntryQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    201))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }
}
