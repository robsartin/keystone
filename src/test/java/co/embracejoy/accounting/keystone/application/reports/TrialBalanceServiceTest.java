package co.embracejoy.accounting.keystone.application.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrialBalanceService")
class TrialBalanceServiceTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final LocalDate ASOF = LocalDate.parse("2026-05-13");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  @Test
  @DisplayName("query() returns the read model's rows unchanged")
  void shouldDelegateToReadModelAndReturnRowsUnchanged() {
    TrialBalanceRow row = new TrialBalanceRow(new AccountCode("1000"), USD, 1000L, 0L, 1000L, 0L);
    TrialBalanceReadModel fake = (tenantId, asOf, includeZero) -> List.of(row);
    TrialBalanceService service = new TrialBalanceService(fake);

    List<TrialBalanceRow> rows = service.query(TENANT, ASOF, false);

    assertThat(rows).containsExactly(row);
  }

  @Test
  @DisplayName("query() passes tenantId, asOf and includeZero through to the read model")
  void shouldPassArgsThrough() {
    AtomicReference<TenantId> capturedTenant = new AtomicReference<>();
    AtomicReference<LocalDate> capturedAsOf = new AtomicReference<>();
    AtomicBoolean capturedIncludeZero = new AtomicBoolean();
    TrialBalanceReadModel spy =
        (tenantId, asOf, includeZero) -> {
          capturedTenant.set(tenantId);
          capturedAsOf.set(asOf);
          capturedIncludeZero.set(includeZero);
          return List.of();
        };
    TrialBalanceService service = new TrialBalanceService(spy);

    service.query(TENANT, ASOF, true);

    assertThat(capturedTenant.get()).isEqualTo(TENANT);
    assertThat(capturedAsOf.get()).isEqualTo(ASOF);
    assertThat(capturedIncludeZero.get()).isTrue();
  }

  @Test
  @DisplayName("rejects null readModel in constructor")
  void shouldThrowWhenReadModelIsNull() {
    assertThrows(NullPointerException.class, () -> new TrialBalanceService(null));
  }

  @Test
  @DisplayName("rejects null tenantId")
  void shouldThrowWhenTenantIdIsNull() {
    TrialBalanceService service =
        new TrialBalanceService((tenantId, asOf, includeZero) -> List.of());
    assertThrows(NullPointerException.class, () -> service.query(null, ASOF, false));
  }

  @Test
  @DisplayName("rejects null asOf")
  void shouldThrowWhenAsOfIsNull() {
    TrialBalanceService service =
        new TrialBalanceService((tenantId, asOf, includeZero) -> List.of());
    assertThrows(NullPointerException.class, () -> service.query(TENANT, null, false));
  }
}
