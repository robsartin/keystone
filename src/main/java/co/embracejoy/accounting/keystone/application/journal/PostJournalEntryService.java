package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Use case: post a balanced journal entry, persisting it through the repository port. */
public final class PostJournalEntryService {

  private final JournalEntryRepository repository;

  public PostJournalEntryService(JournalEntryRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public Result<JournalEntry, JournalError> post(
      LocalDate occurredOn, String description, List<Posting> postings) {
    return JournalEntry.of(occurredOn, description, postings).map(repository::save);
  }
}
