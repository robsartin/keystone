package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /journal-entries/&#123;id&#125;/reverse}. */
public record ReverseJournalEntryRequest(@NotBlank @Size(max = 500) String reason) {}
