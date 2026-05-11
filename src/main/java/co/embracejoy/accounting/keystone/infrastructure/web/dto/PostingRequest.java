package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** Inbound posting line within a {@link PostJournalEntryRequest}. */
public record PostingRequest(
    @NotBlank(message = "account is required") String account,
    @NotNull(message = "side is required")
        @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "side must be DEBIT or CREDIT")
        String side,
    @PositiveOrZero(message = "minorUnits must be zero or positive") long minorUnits) {}
