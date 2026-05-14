package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PeriodRepositoryAdapter implements PeriodRepository {

  private final JpaPeriodRepository jpa;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public PeriodRepositoryAdapter(
      JpaPeriodRepository jpa,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jpa = jpa;
    this.tenantContext = tenantContext;
    this.rlsInterceptor = rlsInterceptor;
  }

  @Override
  public Period save(Period period) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, period.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    PeriodEntity saved = jpa.save(PeriodEntityMapper.toEntity(period));
    return PeriodEntityMapper.toDomain(saved);
  }

  @Override
  public Period update(Period period) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, period.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    UUID tenantUuid = tid.value();
    String ym = period.yearMonth().toString();
    PeriodEntity entity =
        jpa.findByTenantIdAndYearMonth(tenantUuid, ym)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Period to update does not exist: " + period.yearMonth()));
    entity.setStatus(period.status().name());
    entity.setClosedAt(period.closedAt().orElse(null));
    entity.setClosedBy(period.closedBy().orElse(null));
    entity.setReopenedAt(period.reopenedAt().orElse(null));
    entity.setReopenedBy(period.reopenedBy().orElse(null));
    return PeriodEntityMapper.toDomain(jpa.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Period> findByYearMonth(TenantId tenantId, YearMonth yearMonth) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findByTenantIdAndYearMonth(tenantId.value(), yearMonth.toString())
        .map(PeriodEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Period> findAllClosed(TenantId tenantId) {
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findAllByTenantIdAndStatusOrderByYearMonthDesc(tenantId.value(), "CLOSED").stream()
        .map(PeriodEntityMapper::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Period> findLatestClosed(TenantId tenantId) {
    List<Period> all = findAllClosed(tenantId);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  private void validateTenantMatch(TenantId contextTid, TenantId periodTid) {
    if (!contextTid.equals(periodTid)) {
      throw new IllegalStateException(
          "tenant mismatch — period is " + periodTid + ", context is " + contextTid);
    }
  }
}
