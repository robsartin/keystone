package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.JournalValidationContext;
import co.embracejoy.accounting.keystone.domain.journal.JournalValidationMode;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Use case: post a balanced journal entry, persisting it through the repository port. */
public final class PostJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final AccountRepository accountRepository;
  private final PeriodService periodService;
  private final Currency baseCurrency;

  public PostJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService,
      Currency baseCurrency) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
    this.periodService = Objects.requireNonNull(periodService, "periodService");
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
  }

  public Result<PersistedJournalEntry, JournalError> post(
      TenantId tenantId, LocalDate occurredOn, String description, List<Posting> postings) {
    Set<AccountCode> codes =
        postings.stream().map(Posting::account).collect(Collectors.toCollection(HashSet::new));
    Map<AccountCode, Account> accounts = accountRepository.findByCodeIn(codes);
    Set<AccountCode> nonLeafCodes =
        accounts.keySet().stream()
            .filter(accountRepository::hasChildren)
            .collect(Collectors.toUnmodifiableSet());
    PeriodStatus periodStatus =
        periodService.findByYearMonth(tenantId, YearMonth.from(occurredOn)).status();
    JournalValidationContext ctx =
        new JournalValidationContext(
            accounts, nonLeafCodes, periodStatus, baseCurrency, JournalValidationMode.STRICT);
    return JournalEntry.of(occurredOn, description, postings, ctx).map(journalRepository::save);
  }
}
