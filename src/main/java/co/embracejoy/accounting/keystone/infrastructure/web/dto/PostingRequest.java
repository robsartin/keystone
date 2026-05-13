package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Inbound posting line within a {@link PostJournalEntryRequest}.
 *
 * <p>Multi-currency: {@code currency} is the transaction currency (ISO 4217). {@code
 * baseMinorUnits} is the same amount expressed in the configured base currency; the server
 * validates that the base-currency choice matches the configured base.
 */
public record PostingRequest(
    @NotBlank(message = "account is required") String account,
    @NotNull(message = "side is required")
        @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "side must be DEBIT or CREDIT")
        String side,
    @PositiveOrZero(message = "minorUnits must be zero or positive") long minorUnits,
    @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency,
    @PositiveOrZero(message = "baseMinorUnits must be zero or positive") long baseMinorUnits) {}
