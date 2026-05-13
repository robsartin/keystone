package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-pure container for data {@link JournalEntry#of(java.time.LocalDate, String,
 * java.util.List, JournalValidationContext)} needs to validate a new entry.
 *
 * <p>The application service does the I/O (account + period lookups, base currency from config) and
 * packs results here.
 */
public record JournalValidationContext(
    Map<AccountCode, Account> accounts,
    Set<AccountCode> nonLeafCodes,
    PeriodStatus periodStatus,
    Currency baseCurrency,
    boolean permissiveMode) {

  public JournalValidationContext {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(nonLeafCodes, "nonLeafCodes");
    Objects.requireNonNull(periodStatus, "periodStatus");
    Objects.requireNonNull(baseCurrency, "baseCurrency");
    accounts = Map.copyOf(accounts);
    nonLeafCodes = Set.copyOf(nonLeafCodes);
  }

  /** Back-compat 4-arg constructor; defaults baseCurrency to USD. */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts,
      Set<AccountCode> nonLeafCodes,
      PeriodStatus periodStatus,
      boolean permissiveMode) {
    this(accounts, nonLeafCodes, periodStatus, Currency.getInstance("USD"), permissiveMode);
  }

  /** Back-compat 2-arg constructor; defaults to OPEN period, USD base, non-permissive. */
  public JournalValidationContext(
      Map<AccountCode, Account> accounts, Set<AccountCode> nonLeafCodes) {
    this(accounts, nonLeafCodes, PeriodStatus.OPEN, Currency.getInstance("USD"), false);
  }

  /** Permissive context — skip both account and period checks. */
  public static JournalValidationContext permissive() {
    return new JournalValidationContext(
        Map.of(), Set.of(), PeriodStatus.OPEN, Currency.getInstance("USD"), true);
  }
}
