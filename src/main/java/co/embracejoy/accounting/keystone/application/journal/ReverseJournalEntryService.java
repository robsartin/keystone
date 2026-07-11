package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: reverse an existing journal entry.
 *
 * <p>Blocks reversing an already-reversed original ({@link JournalError.AlreadyReversed}) and
 * missing originals ({@link JournalError.NotFound}). Delegates persistence + full
 * balance/period/account validation to {@link PostJournalEntryService#postReversal}. The reversal's
 * {@code occurredOn} is today's date (per {@link Clock}), never the original's — so a reversal of a
 * historical entry lands in the current period and cannot rewrite closed-period history.
 *
 * <p><b>Precondition:</b> {@code reason} must be non-null and non-blank. Enforced by
 * {@code @NotBlank} on the HTTP DTO and by {@link JournalEntry#reverse}, which throws {@link
 * IllegalArgumentException} on blank. This service assumes caller-side validation; a blank reason
 * surfaces as an unchecked exception rather than a {@link Result} failure. Additional callers must
 * validate reason before calling.
 */
public final class ReverseJournalEntryService {

  private final JournalEntryRepository journalRepository;
  private final PostJournalEntryService postJournalEntryService;
  private final Clock clock;

  public ReverseJournalEntryService(
      JournalEntryRepository journalRepository,
      PostJournalEntryService postJournalEntryService,
      Clock clock) {
    this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
    this.postJournalEntryService =
        Objects.requireNonNull(postJournalEntryService, "postJournalEntryService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Result<PersistedJournalEntry, JournalError> reverse(
      TenantId tenantId, JournalEntryId originalId, String reason, String actor) {
    Optional<PersistedJournalEntry> maybeOriginal =
        journalRepository.findById(tenantId, originalId);
    if (maybeOriginal.isEmpty()) {
      return Result.failure(new JournalError.NotFound(originalId));
    }
    if (journalRepository.existsReversalOf(tenantId, originalId)) {
      return Result.failure(new JournalError.AlreadyReversed(originalId));
    }
    LocalDate today = LocalDate.now(clock);
    JournalEntry reversal =
        JournalEntry.reverse(originalId, reason, today, maybeOriginal.get().entry());
    ReversalMetadata metadata = new ReversalMetadata(originalId, reason);
    return postJournalEntryService.postReversal(tenantId, reversal, metadata, actor);
  }
}
