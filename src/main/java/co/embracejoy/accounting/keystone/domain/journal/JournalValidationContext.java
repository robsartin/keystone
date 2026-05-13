package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry. The application service
 * does the I/O (account + period lookups) and packs results in here.
 */
public record JournalValidationContext(
    Map<AccountCode, Account> accounts,
    Set<AccountCode> nonLeafCodes,
    PeriodStatus periodStatus,
    boolean permissiveMode) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    Objects.requireNonNull(periodStatus, "periodStatus");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  /** Two-arg overload kept for back-compat with non-period callers (defaults to OPEN). */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {
    this(accounts, nonLeafCodes, PeriodStatus.OPEN, false);
  }

  /** Permissive context — skip both account and period checks (used by historical tests). */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(Map.of(), Set.of(), PeriodStatus.OPEN, true);
  }
}
