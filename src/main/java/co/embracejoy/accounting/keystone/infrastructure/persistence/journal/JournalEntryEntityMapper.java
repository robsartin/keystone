package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.util.Currency;
import java.util.UUID;

/** Translates between the persistence entity graph and the domain model. */
final class JournalEntryEntityMapper {

  private JournalEntryEntityMapper() {
    // static utility class; no instances
  }

  static JournalEntryEntity toEntity(JournalEntry entry, UUID id) {
    // Phase A: use the first posting's amount currency as the entry-level currency for the
    // entity column (Phase B's V5 migration will move currency to postings and drop this column).
    String currencyCode =
        entry.postings().isEmpty()
            ? "USD"
            : entry.postings().get(0).amount().currency().getCurrencyCode();
    JournalEntryEntity je =
        new JournalEntryEntity(id, entry.occurredOn(), entry.description(), currencyCode);
    java.util.List<Posting> postings = entry.postings();
    for (int i = 0; i < postings.size(); i++) {
      Posting p = postings.get(i);
      je.addPosting(
          new PostingEntity(
              UuidV7Generator.create(),
              p.account().value(),
              p.side().name(),
              p.amount().minorUnits(),
              i));
    }
    return je;
  }

  static PersistedJournalEntry toDomain(JournalEntryEntity entity) {
    Currency currency = Currency.getInstance(entity.getCurrency());
    java.util.List<Posting> postings =
        entity.getPostings().stream()
            .map(
                pe -> {
                  Money money = new Money(pe.getAmountMinorUnits(), currency);
                  return new Posting(
                      new AccountCode(pe.getAccountCode()),
                      Side.valueOf(pe.getSide()),
                      money,
                      money);
                })
            .toList();
    // currency read from entity but not forwarded — JournalEntry dropped the field in Slice 6.
    // Phase B's V5 migration will move currency to the postings table.
    JournalEntry entry =
        new JournalEntry(entity.getOccurredOn(), entity.getDescription(), postings);
    return new PersistedJournalEntry(new JournalEntryId(entity.getId()), entry);
  }
}
