package co.embracejoy.accounting.keystone.domain.period;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link Period} aggregates. */
public interface PeriodRepository {

  /** Persist a new period (only invoked when the status changes from the synthesized OPEN). */
  Period save(Period period);

  /** Update an existing period's status + audit fields. */
  Period update(Period period);

  /**
   * Look up the persisted row for a YearMonth; absent if no row exists (status is implicitly OPEN).
   */
  Optional<Period> findByYearMonth(TenantId tenantId, YearMonth yearMonth);

  /** All closed periods for the given tenant, sorted by YearMonth descending. */
  List<Period> findAllClosed(TenantId tenantId);

  /** The latest-closed period (max by YearMonth) for the given tenant, if any. */
  Optional<Period> findLatestClosed(TenantId tenantId);
}
