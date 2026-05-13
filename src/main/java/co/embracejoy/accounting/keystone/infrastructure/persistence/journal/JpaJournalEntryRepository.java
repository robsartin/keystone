package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.infrastructure.shared.UuidV7Generator;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed adapter for {@link JournalEntryRepository}. */
@Repository
@Transactional
public class JpaJournalEntryRepository implements JournalEntryRepository {

  private final JournalEntryJpaRepository jpa;
  private final Currency baseCurrency;

  public JpaJournalEntryRepository(JournalEntryJpaRepository jpa, Currency baseCurrency) {
    this.jpa = jpa;
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
  }

  @Override
  public PersistedJournalEntry save(JournalEntry entry) {
    var entity = JournalEntryEntityMapper.toEntity(entry, UuidV7Generator.create());
    var saved = jpa.save(entity);
    return JournalEntryEntityMapper.toDomain(saved, baseCurrency);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PersistedJournalEntry> findById(JournalEntryId id) {
    return jpa.findById(id.value()).map(e -> JournalEntryEntityMapper.toDomain(e, baseCurrency));
  }

  @Override
  @Transactional(readOnly = true)
  public Set<YearMonth> distinctOccurredMonths() {
    return jpa.findDistinctOccurredMonthStrings().stream()
        .map(YearMonth::parse)
        .collect(Collectors.toUnmodifiableSet());
  }
}
