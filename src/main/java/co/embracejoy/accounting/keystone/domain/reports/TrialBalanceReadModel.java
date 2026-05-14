package co.embracejoy.accounting.keystone.domain.reports;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;

/**
 * Read port for trial-balance projections.
 *
 * <p>Returns one row per {@code (accountCode, currency)} pair with at least one posting on or
 * before {@code asOf}. When {@code includeZero} is false, rows whose transaction-currency balance
 * is exactly zero are omitted. Rows are ordered by {@code accountCode}, then {@code currency} —
 * stable, predictable iteration.
 */
public interface TrialBalanceReadModel {

  /**
   * Project the trial balance for a specific tenant.
   *
   * @param tenantId only postings belonging to this tenant are included.
   * @param asOf only postings on journal entries with {@code occurred_on <= asOf} are counted.
   * @param includeZero when true, include rows whose transaction-currency balance is zero.
   * @return list of rows in {@code (accountCode, currency)} order; possibly empty, never null.
   */
  List<TrialBalanceRow> fetch(TenantId tenantId, LocalDate asOf, boolean includeZero);
}
