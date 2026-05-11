package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Inbound payload for {@code POST /journal-entries}. */
public record PostJournalEntryRequest(
    @NotNull(message = "occurredOn is required") LocalDate occurredOn,
    @NotBlank(message = "description is required")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,
    @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency,
    @NotEmpty(message = "postings must not be empty") @Valid List<PostingRequest> postings) {

  @JsonCreator
  public PostJournalEntryRequest(
      @JsonProperty("occurredOn") LocalDate occurredOn,
      @JsonProperty("description") String description,
      @JsonProperty("currency") String currency,
      @JsonProperty("postings") List<PostingRequest> postings) {
    this.occurredOn = occurredOn;
    this.description = description;
    this.currency = currency;
    this.postings = postings == null ? List.of() : List.copyOf(postings);
  }
}
