package co.embracejoy.accounting.keystone.infrastructure.config;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires application-layer (domain-pure) services as Spring beans. */
@Configuration
public class ApplicationConfig {

  @Bean
  public PostJournalEntryService postJournalEntryService(
      JournalEntryRepository journalRepository, AccountRepository accountRepository) {
    return new PostJournalEntryService(journalRepository, accountRepository);
  }

  @Bean
  public AccountService accountService(AccountRepository accountRepository) {
    return new AccountService(accountRepository);
  }
}
