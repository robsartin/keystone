package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.time.YearMonth;
import java.util.Optional;

final class PeriodEntityMapper {

  private PeriodEntityMapper() {
    // utility class — no instances
  }

  static PeriodEntity toEntity(Period p) {
    return new PeriodEntity(
        p.yearMonth().toString(),
        p.status().name(),
        p.closedAt().orElse(null),
        p.closedBy().orElse(null),
        p.reopenedAt().orElse(null),
        p.reopenedBy().orElse(null));
  }

  static Period toDomain(PeriodEntity e) {
    return new Period(
        YearMonth.parse(e.getYearMonth()),
        PeriodStatus.valueOf(e.getStatus()),
        Optional.ofNullable(e.getClosedAt()),
        Optional.ofNullable(e.getClosedBy()),
        Optional.ofNullable(e.getReopenedAt()),
        Optional.ofNullable(e.getReopenedBy()));
  }
}
