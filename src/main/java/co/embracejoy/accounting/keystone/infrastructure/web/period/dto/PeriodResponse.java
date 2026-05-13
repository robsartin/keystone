package co.embracejoy.accounting.keystone.infrastructure.web.period.dto;

import co.embracejoy.accounting.keystone.domain.period.Period;
import java.time.Instant;

public record PeriodResponse(
    String yearMonth,
    String status,
    Instant closedAt,
    String closedBy,
    Instant reopenedAt,
    String reopenedBy) {

  public static PeriodResponse of(Period p) {
    return new PeriodResponse(
        p.yearMonth().toString(),
        p.status().name(),
        p.closedAt().orElse(null),
        p.closedBy().orElse(null),
        p.reopenedAt().orElse(null),
        p.reopenedBy().orElse(null));
  }
}
