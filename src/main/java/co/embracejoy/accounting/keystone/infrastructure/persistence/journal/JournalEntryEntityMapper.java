package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** Translates between the persistence entity graph and the domain model. */
final class JournalEntryEntityMapper {

  private JournalEntryEntityMapper() {
    // static utility class; no instances
  }

  static JournalEntryEntity toEntity(
      JournalEntry entry, UUID id, String actor, Optional<ReversalMetadata> reverses) {
    UUID reversesId =
        reverses.map(ReversalMetadata::reversesId).map(JournalEntryId::value).orElse(null);
    String reversalReason = reverses.map(ReversalMetadata::reason).orElse(null);
    JournalEntryEntity je =
        new JournalEntryEntity(
            id,
            entry.tenantId().value(),
            entry.occurredOn(),
            entry.description(),
            reversesId,
            reversalReason,
            actor);
    java.util.List<Posting> postings = entry.postings();
    for (int i = 0; i < postings.size(); i++) {
      Posting p = postings.get(i);
      // tenantId is stamped on the posting by JournalEntryEntity.addPosting()
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
        new JournalEntry(
            new TenantId(entity.getTenantId()),
            entity.getOccurredOn(),
            entity.getDescription(),
            postings);
    Optional<ReversalMetadata> reverses =
        entity.getReversesId() == null
            ? Optional.empty()
            : Optional.of(
                new ReversalMetadata(
                    new JournalEntryId(entity.getReversesId()), entity.getReversalReason()));
    return new PersistedJournalEntry(
        new JournalEntryId(entity.getId()), entry, reverses, Optional.empty());
  }
}
