package co.embracejoy.accounting.keystone.application.reports;

import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Use-case service for the trial-balance report.
 *
 * <p>Thin facade: delegates to the {@link TrialBalanceReadModel} port. The seam exists so the web
 * layer depends on application, not on infrastructure — ArchUnit enforces this in {@code
 * HexagonalArchitectureTest}.
 */
public final class TrialBalanceService {

  private final TrialBalanceReadModel readModel;

  public TrialBalanceService(TrialBalanceReadModel readModel) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
  }

  public List<TrialBalanceRow> query(TenantId tenantId, LocalDate asOf, boolean includeZero) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(asOf, "asOf");
    return readModel.fetch(tenantId, asOf, includeZero);
  }
}
