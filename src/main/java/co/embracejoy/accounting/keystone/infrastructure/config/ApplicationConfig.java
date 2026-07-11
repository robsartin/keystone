package co.embracejoy.accounting.keystone.infrastructure.config;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.application.journal.JournalEntryQueryService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.application.journal.ReverseJournalEntryService;
import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.application.reports.TrialBalanceService;
import co.embracejoy.accounting.keystone.application.security.UserRoleService;
import co.embracejoy.accounting.keystone.application.tenancy.TenantService;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryReadModel;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires application-layer (domain-pure) services as Spring beans. */
@Configuration
public class ApplicationConfig {

  @Bean
  public Currency keystoneBaseCurrency(KeystoneProperties properties) {
    return properties.baseCurrency();
  }

  @Bean
  public PeriodService periodService(
      PeriodRepository periodRepository, JournalEntryRepository journalRepository) {
    return new PeriodService(periodRepository, journalRepository);
  }

  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService,
      KeystoneProperties properties) {
    return new PostJournalEntryService(
        journalRepository, accountRepository, periodService, properties.baseCurrency());
  }

  @Bean
  public ReverseJournalEntryService reverseJournalEntryService(
      JournalEntryRepository journalRepository,
      PostJournalEntryService postJournalEntryService,
      Clock clock) {
    return new ReverseJournalEntryService(journalRepository, postJournalEntryService, clock);
  }

  @Bean
  public AccountService accountService(AccountRepository accountRepository) {
    return new AccountService(accountRepository);
  }

  @Bean
  public TrialBalanceService trialBalanceService(TrialBalanceReadModel readModel) {
    return new TrialBalanceService(readModel);
  }

  @Bean
  public JournalEntryQueryService journalEntryQueryService(JournalEntryReadModel readModel) {
    return new JournalEntryQueryService(readModel);
  }

  @Bean
  public Clock keystoneClock() {
    return Clock.systemUTC();
  }

  @Bean
  public Supplier<UUID> keystoneUuidSupplier() {
    return UuidV7Generator::create;
  }

  @Bean
  public TenantService tenantService(
      TenantRepository tenantRepository, Clock clock, Supplier<UUID> uuidSupplier) {
    return new TenantService(tenantRepository, clock, uuidSupplier);
  }

  @Bean
  public UserRoleService userRoleService(TenantUserRoleRepository roleRepository, Clock clock) {
    return new UserRoleService(roleRepository, clock);
  }
}
