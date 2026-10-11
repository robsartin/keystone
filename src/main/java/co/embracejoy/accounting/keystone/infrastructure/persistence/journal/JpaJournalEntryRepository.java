package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.shared.Result;
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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** JPA-backed adapter for {@link JournalEntryRepository}. */
@Repository
@Transactional
public class JpaJournalEntryRepository implements JournalEntryRepository {

  /** Name of the partial unique index created by V11; see ADR-0023. */
  private static final String REVERSES_ID_UNIQUE = "journal_entries_reverses_id_unique";

  private final JournalEntryJpaRepository jpa;
  private final Currency baseCurrency;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;
  private final TransactionTemplate writeTx;

  public JpaJournalEntryRepository(
      JournalEntryJpaRepository jpa,
      Currency baseCurrency,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor,
      PlatformTransactionManager transactionManager) {
    this.jpa = jpa;
    this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
    this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
    this.rlsInterceptor = Objects.requireNonNull(rlsInterceptor, "rlsInterceptor");
    this.writeTx = new TransactionTemplate(transactionManager);
  }

  @Override
  public PersistedJournalEntry save(JournalEntry entry, String actor) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, entry.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    var entity =
        JournalEntryEntityMapper.toEntity(entry, UuidV7Generator.create(), actor, Optional.empty());
    var saved = jpa.save(entity);
    return JournalEntryEntityMapper.toDomain(saved, baseCurrency);
  }

  /**
   * Not transactional itself: the write runs in its own short transaction (via {@link
   * TransactionTemplate}) so that a unique-index violation -- which aborts that transaction -- is
   * caught <em>outside</em> it. Catching inside a {@code @Transactional} method would leave the
   * transaction rollback-only and the caller would see {@code UnexpectedRollbackException}. See
   * ADR-0023.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Result<PersistedJournalEntry, JournalError> saveReversal(
      JournalEntry reversal, ReversalMetadata metadata, String actor) {
    try {
      return writeTx.execute(status -> insertReversal(reversal, metadata, actor));
    } catch (DataIntegrityViolationException e) {
      if (isReversesIdUniqueViolation(e)) {
        return Result.failure(new JournalError.AlreadyReversed(metadata.reversesId()));
      }
      throw e;
    }
  }

  private Result<PersistedJournalEntry, JournalError> insertReversal(
      JournalEntry reversal, ReversalMetadata metadata, String actor) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, reversal.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    var entity =
        JournalEntryEntityMapper.toEntity(
            reversal, UuidV7Generator.create(), actor, Optional.of(metadata));
    // Flush inside the transaction so the constraint violation surfaces here, not at commit.
    var saved = jpa.saveAndFlush(entity);
    return Result.success(JournalEntryEntityMapper.toDomain(saved, baseCurrency));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsReversalOf(TenantId tenantId, JournalEntryId originalId) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.existsByTenantIdAndReversesId(tenantId.value(), originalId.value());
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

  private static boolean isReversesIdUniqueViolation(DataIntegrityViolationException e) {
    return e.getCause() instanceof ConstraintViolationException cve
        && REVERSES_ID_UNIQUE.equals(cve.getConstraintName());
  }

  private void validateTenantMatch(TenantId contextTid, TenantId entryTid) {
    if (!contextTid.equals(entryTid)) {
      throw new IllegalStateException(
          "tenant mismatch — entry is " + entryTid + ", context is " + contextTid);
    }
  }
}
