package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Filter + pagination shape for the journal-entry list surface. All filters are optional
 * (Optional.empty means "no filter"); limit is bounded by the caller. Cursor pagination uses {@code
 * after} — the id of the last row on the previous page.
 */
public record JournalEntryQuery(
    Optional<LocalDate> from,
    Optional<LocalDate> to,
    Optional<AccountCode> account,
    Optional<String> q,
    Optional<Long> amountMin,
    Optional<Long> amountMax,
    Optional<JournalEntryId> after,
    int limit) {

  public JournalEntryQuery {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(q, "q");
    Objects.requireNonNull(amountMin, "amountMin");
    Objects.requireNonNull(amountMax, "amountMax");
    Objects.requireNonNull(after, "after");
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200 (got " + limit + ")");
    }
  }
}
