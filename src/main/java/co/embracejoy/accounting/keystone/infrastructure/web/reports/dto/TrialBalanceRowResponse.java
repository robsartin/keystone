package co.embracejoy.accounting.keystone.infrastructure.web.reports.dto;

import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;

/**
 * Outbound wire shape for one trial-balance row.
 *
 * <p>{@code balance} and {@code baseBalance} are flattened onto the wire (rather than expecting
 * clients to subtract debits and credits themselves). Amount fields are integer minor units; see
 * ADR-0003.
 */
public record TrialBalanceRowResponse(
    String accountCode,
    String currency,
    long debits,
    long credits,
    long balance,
    long baseDebits,
    long baseCredits,
    long baseBalance) {

  public static TrialBalanceRowResponse of(TrialBalanceRow row) {
    return new TrialBalanceRowResponse(
        row.accountCode().value(),
        row.currency().getCurrencyCode(),
        row.debits(),
        row.credits(),
        row.balance(),
        row.baseDebits(),
        row.baseCredits(),
        row.baseBalance());
  }
}
