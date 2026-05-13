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
    JournalEntryEntity je = new JournalEntryEntity(id, entry.occurredOn(), entry.description());
    java.util.List<Posting> postings = entry.postings();
    for (int i = 0; i < postings.size(); i++) {
      Posting p = postings.get(i);
      je.addPosting(
          new PostingEntity(
              UuidV7Generator.create(),
              p.account().value(),
              p.side().name(),
              p.amount().minorUnits(),
              p.amount().currency().getCurrencyCode(),
              p.baseAmount().minorUnits(),
              i));
    }
    return je;
  }

  /**
   * Reconstitutes a domain {@link PersistedJournalEntry} from the entity graph.
   *
   * @param entity the JPA entity
   * @param baseCurrency the configured base currency (from {@code keystone.base-currency}), used to
   *     set {@code Posting.baseAmount.currency()} on reconstitution
   */
  static PersistedJournalEntry toDomain(JournalEntryEntity entity, Currency baseCurrency) {
    java.util.List<Posting> postings =
        entity.getPostings().stream()
            .map(
                pe -> {
                  Currency txCurrency = Currency.getInstance(pe.getCurrency());
                  Money amount = new Money(pe.getAmountMinorUnits(), txCurrency);
                  Money baseAmount = new Money(pe.getBaseMinorUnits(), baseCurrency);
                  return new Posting(
                      new AccountCode(pe.getAccountCode()),
                      Side.valueOf(pe.getSide()),
                      amount,
                      baseAmount);
                })
            .toList();
    JournalEntry entry =
        new JournalEntry(entity.getOccurredOn(), entity.getDescription(), postings);
    return new PersistedJournalEntry(new JournalEntryId(entity.getId()), entry);
  }
}
