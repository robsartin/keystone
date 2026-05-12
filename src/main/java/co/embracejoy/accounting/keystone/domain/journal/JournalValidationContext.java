package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Map;
import java.util.Objects;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry. The application service
 * does the I/O (account lookups, future period lookup) and packs results in here.
 *
 * <p>Slice 3 will add a {@code PeriodStatus periodStatus} field.
 */
public record JournalValidationContext(Map<AccountCode, Account> accounts) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    accounts = Map.copyOf(accounts);
  }

  /** Empty-accounts context for tests and callers that don't need account validation yet. */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of());
  }
}
