package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry. The application service
 * does the I/O (account lookups, future period lookup) and packs results in here.
 *
 * <p>Slice 3 will add a {@code PeriodStatus periodStatus} field.
 */
public record JournalValidationContext(
    Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes, boolean permissiveMode) {

  public JournalValidationContext(
      Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {
    this(accounts, nonLeafCodes, false);
  }

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  /**
   * Returns a permissive context that skips account validation. Use for backward compatibility with
   * callers that don't need account checks (e.g., the three-arg {@code of(...)} overload).
   */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of(), Set.of(), true);
  }
}
