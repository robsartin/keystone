package co.embracejoy.accounting.keystone.application.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostJournalEntryService")
class PostJournalEntryServiceTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CASH = new AccountCode("1000");
  private static final AccountCode EQUITY = new AccountCode("3000");
  private static final LocalDate TODAY = LocalDate.parse("2026-05-09");

  private static final class FakeRepo implements JournalEntryRepository {
    final List<JournalEntry> saved = new ArrayList<>();

    @Override
    public JournalEntry save(JournalEntry entry) {
      saved.add(entry);
      return entry;
    }

    @Override
    public Optional<JournalEntry> findById(String id) {
      return Optional.empty();
    }
  }

  private static Posting debit(AccountCode a, long amt) {
    return new Posting(a, Side.DEBIT, new Money(amt, USD));
  }

  private static Posting credit(AccountCode a, long amt) {
    return new Posting(a, Side.CREDIT, new Money(amt, USD));
  }

  @Test
  @DisplayName("persists and returns Success when request is valid")
  void shouldPersistAndReturnSuccessWhenRequestIsValid() {
    FakeRepo repo = new FakeRepo();
    PostJournalEntryService service = new PostJournalEntryService(repo);

    Result<JournalEntry, JournalError> r =
        service.post(TODAY, "opening", List.of(debit(CASH, 1000L), credit(EQUITY, 1000L)));

    assertInstanceOf(Result.Success.class, r);
    assertEquals(1, repo.saved.size());
    assertSame(((Result.Success<JournalEntry, JournalError>) r).value(), repo.saved.get(0));
  }

  @Test
  @DisplayName("returns Failure and does not persist when entry is unbalanced")
  void shouldReturnFailureAndNotPersistWhenUnbalanced() {
    FakeRepo repo = new FakeRepo();
    PostJournalEntryService service = new PostJournalEntryService(repo);

    Result<JournalEntry, JournalError> r =
        service.post(TODAY, "bad", List.of(debit(CASH, 1000L), credit(EQUITY, 999L)));

    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.Unbalanced.class, ((Result.Failure<JournalEntry, JournalError>) r).error());
    assertEquals(0, repo.saved.size());
  }

  @Test
  @DisplayName("returns Failure when postings are empty")
  void shouldReturnFailureWhenPostingsEmpty() {
    FakeRepo repo = new FakeRepo();
    PostJournalEntryService service = new PostJournalEntryService(repo);

    Result<JournalEntry, JournalError> r = service.post(TODAY, "empty", List.of());

    assertInstanceOf(Result.Failure.class, r);
    assertInstanceOf(
        JournalError.NoPostings.class, ((Result.Failure<JournalEntry, JournalError>) r).error());
    assertEquals(0, repo.saved.size());
  }
}
