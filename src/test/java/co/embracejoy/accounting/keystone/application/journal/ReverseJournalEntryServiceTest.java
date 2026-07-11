package co.embracejoy.accounting.keystone.application.journal;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@DisplayName("ReverseJournalEntryService")
class ReverseJournalEntryServiceTest {

  private final JournalEntryRepository repo = Mockito.mock(JournalEntryRepository.class);
  private final PostJournalEntryService poster = Mockito.mock(PostJournalEntryService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);
  private final ReverseJournalEntryService service =
      new ReverseJournalEntryService(repo, poster, clock);

  private static final TenantId TENANT = Tenants.DEFAULT_TENANT_ID;
  private static final JournalEntryId ORIGINAL_ID =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000abc"));
  private static final String REASON = "posted to wrong account";
  private static final String ACTOR = "reverser-actor";

  @Test
  @DisplayName("returns NotFound when original does not exist")
  void shouldReturnNotFoundWhenOriginalDoesNotExist() {
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.empty());

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isEqualTo(new JournalError.NotFound(ORIGINAL_ID));
    Mockito.verify(poster, Mockito.never())
        .postReversal(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  @DisplayName("returns AlreadyReversed when a reversal of the original already exists")
  void shouldReturnAlreadyReversedWhenOriginalHasBeenReversed() {
    PersistedJournalEntry original = buildOriginal();
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(true);

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isEqualTo(new JournalError.AlreadyReversed(ORIGINAL_ID));
    Mockito.verify(poster, Mockito.never())
        .postReversal(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  @DisplayName("returns success and delegates to postReversal with actor + today's date")
  void shouldDelegateToPostReversalWithActorAndTodaysDate() {
    PersistedJournalEntry original = buildOriginal();
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(false);

    PersistedJournalEntry persisted =
        new PersistedJournalEntry(new JournalEntryId(UUID.randomUUID()), original.entry());
    Mockito.when(
            poster.postReversal(
                Mockito.eq(TENANT), Mockito.any(), Mockito.any(), Mockito.eq(ACTOR)))
        .thenReturn(Result.success(persisted));

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Success.class);

    ArgumentCaptor<JournalEntry> reversalCaptor = ArgumentCaptor.forClass(JournalEntry.class);
    ArgumentCaptor<ReversalMetadata> metadataCaptor =
        ArgumentCaptor.forClass(ReversalMetadata.class);
    Mockito.verify(poster)
        .postReversal(
            Mockito.eq(TENANT),
            reversalCaptor.capture(),
            metadataCaptor.capture(),
            Mockito.eq(ACTOR));

    assertThat(reversalCaptor.getValue().occurredOn()).isEqualTo(LocalDate.of(2026, 7, 11));
    assertThat(reversalCaptor.getValue().description())
        .isEqualTo("Reversal of #" + ORIGINAL_ID.value() + ": " + REASON);
    assertThat(metadataCaptor.getValue()).isEqualTo(new ReversalMetadata(ORIGINAL_ID, REASON));
  }

  @Test
  @DisplayName("propagates PostingInClosedPeriod when postReversal rejects the reversal")
  void shouldPropagateClosedPeriodError() {
    PersistedJournalEntry original = buildOriginal();
    Mockito.when(repo.findById(TENANT, ORIGINAL_ID)).thenReturn(Optional.of(original));
    Mockito.when(repo.existsReversalOf(TENANT, ORIGINAL_ID)).thenReturn(false);
    Mockito.when(poster.postReversal(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Result.failure(new JournalError.PostingInClosedPeriod(YearMonth.of(2026, 7))));

    Result<PersistedJournalEntry, JournalError> result =
        service.reverse(TENANT, ORIGINAL_ID, REASON, ACTOR);

    assertThat(result).isInstanceOf(Result.Failure.class);
    Result.Failure<PersistedJournalEntry, JournalError> failure =
        (Result.Failure<PersistedJournalEntry, JournalError>) result;
    assertThat(failure.error()).isInstanceOf(JournalError.PostingInClosedPeriod.class);
  }

  private static PersistedJournalEntry buildOriginal() {
    Currency usd = Currency.getInstance("USD");
    Money amount = new Money(4200L, usd);
    Posting debit = new Posting(new AccountCode("1000"), Side.DEBIT, amount, amount);
    Posting credit = new Posting(new AccountCode("3000"), Side.CREDIT, amount, amount);
    JournalEntry entry =
        new JournalEntry(
            TENANT, LocalDate.of(2026, 6, 15), "original entry", List.of(debit, credit));
    return new PersistedJournalEntry(ORIGINAL_ID, entry);
  }
}
