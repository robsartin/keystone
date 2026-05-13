package co.embracejoy.accounting.keystone.infrastructure.config;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.application.period.PeriodService;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires application-layer (domain-pure) services as Spring beans. */
@Configuration
public class ApplicationConfig {

  @Bean
  public PeriodService periodService(
      PeriodRepository periodRepository, JournalEntryRepository journalRepository) {
    return new PeriodService(periodRepository, journalRepository);
  }

  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository,
      AccountRepository accountRepository,
      PeriodService periodService) {
    return new PostJournalEntryService(journalRepository, accountRepository, periodService);
  }

  @Bean
  public AccountService accountService(AccountRepository accountRepository) {
    return new AccountService(accountRepository);
  }
}
