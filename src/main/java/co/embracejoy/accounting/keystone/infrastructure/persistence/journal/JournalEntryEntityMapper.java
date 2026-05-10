package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.util.Currency;
import java.util.UUID;

/** Translates between the persistence entity graph and the domain model. */
final class JournalEntryEntityMapper {

  private JournalEntryEntityMapper() {
    // static utility class; no instances
  }

  static JournalEntryEntity toEntity(JournalEntry entry, UUID id) {
    JournalEntryEntity je =
        new JournalEntryEntity(
            id, entry.occurredOn(), entry.description(), entry.currency().getCurrencyCode());
    for (Posting p : entry.postings()) {
      je.addPosting(
          new PostingEntity(
              UuidV7Generator.create(),
              p.account().value(),
              p.side().name(),
              p.amount().minorUnits()));
    }
    return je;
  }

  static PersistedJournalEntry toDomain(JournalEntryEntity entity) {
    Currency currency = Currency.getInstance(entity.getCurrency());
    java.util.List<Posting> postings =
        entity.getPostings().stream()
            .map(
                pe ->
                    new Posting(
                        new AccountCode(pe.getAccountCode()),
                        Side.valueOf(pe.getSide()),
                        new Money(pe.getAmountMinorUnits(), currency)))
            .toList();
    Result<JournalEntry, JournalError> r =
        JournalEntry.of(entity.getOccurredOn(), entity.getDescription(), postings);
    if (r instanceof Result.Success<JournalEntry, JournalError> s) {
      return new PersistedJournalEntry(new JournalEntryId(entity.getId()), s.value());
    }
    throw new IllegalStateException(
        "Persisted entry failed to reconstitute: "
            + ((Result.Failure<JournalEntry, JournalError>) r).error());
  }
}
