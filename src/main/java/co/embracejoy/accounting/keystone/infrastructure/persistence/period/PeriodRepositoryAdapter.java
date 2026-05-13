package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodRepository;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PeriodRepositoryAdapter implements PeriodRepository {

  private final JpaPeriodRepository jpa;

  public PeriodRepositoryAdapter(JpaPeriodRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Period save(Period period) {
    PeriodEntity saved = jpa.save(PeriodEntityMapper.toEntity(period));
    return PeriodEntityMapper.toDomain(saved);
  }

  @Override
  public Period update(Period period) {
    PeriodEntity entity =
        jpa.findById(period.yearMonth().toString())
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
  public Optional<Period> findByYearMonth(YearMonth yearMonth) {
    return jpa.findById(yearMonth.toString()).map(PeriodEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Period> findAllClosed() {
    return jpa.findAllClosedDesc().stream().map(PeriodEntityMapper::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Period> findLatestClosed() {
    List<Period> all = findAllClosed();
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }
}
