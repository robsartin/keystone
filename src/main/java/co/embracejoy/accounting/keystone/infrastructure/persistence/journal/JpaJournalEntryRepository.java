package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
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
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public JpaJournalEntryRepository(
      JournalEntryJpaRepository jpa,
      Currency baseCurrency,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jpa = jpa;
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
    this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
    this.rlsInterceptor = Objects.requireNonNull(rlsInterceptor, "rlsInterceptor");
  }

  @Override
  public PersistedJournalEntry save(JournalEntry entry) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, entry.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    var entity = JournalEntryEntityMapper.toEntity(entry, UuidV7Generator.create());
    var saved = jpa.save(entity);
    return JournalEntryEntityMapper.toDomain(saved, baseCurrency);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findByTenantIdAndId(tenantId.value(), id.value())
        .map(e -> JournalEntryEntityMapper.toDomain(e, baseCurrency));
  }

  @Override
  @Transactional(readOnly = true)
  public Set<YearMonth> distinctOccurredMonths(TenantId tenantId) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findDistinctOccurredMonthStringsByTenantId(tenantId.value()).stream()
        .map(YearMonth::parse)
        .collect(Collectors.toUnmodifiableSet());
  }

  private void validateTenantMatch(TenantId contextTid, TenantId entryTid) {
    if (!contextTid.equals(entryTid)) {
      throw new IllegalStateException(
          "tenant mismatch — entry is " + entryTid + ", context is " + contextTid);
    }
  }
}
