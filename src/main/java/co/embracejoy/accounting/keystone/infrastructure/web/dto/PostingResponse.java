package co.embracejoy.accounting.keystone.infrastructure.web.dto;

import co.embracejoy.accounting.keystone.domain.journal.Posting;

/**
 * Outbound representation of a single posting within a {@link JournalEntryResponse}.
 *
 * <p>Multi-currency: {@code currency} is the transaction currency; {@code baseMinorUnits} is the
 * amount in the configured base currency (the figure the entry balances on).
 */
public record PostingResponse(
    String account, String side, long minorUnits, String currency, long baseMinorUnits) {

  public static PostingResponse of(Posting p) {
    return new PostingResponse(
        p.account().value(),
        p.side().name(),
        p.amount().minorUnits(),
        p.amount().currency().getCurrencyCode(),
        p.baseAmount().minorUnits());
  }
}
