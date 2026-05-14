package co.embracejoy.accounting.keystone.domain.period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Period")
class PeriodTest {

  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));
  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

  @Test
  @DisplayName("rejects null tenantId")
  void shouldThrowWhenTenantIdIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                null,
                MAY_2026,
                PeriodStatus.OPEN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("rejects null yearMonth")
  void shouldThrowWhenYearMonthIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                TENANT,
                null,
                PeriodStatus.OPEN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("rejects null status")
  void shouldThrowWhenStatusIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                TENANT,
                MAY_2026,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("rejects null Optional fields")
  void shouldThrowWhenAnyOptionalFieldIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Period(
                TENANT,
                MAY_2026,
                PeriodStatus.OPEN,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("CLOSED requires closedAt and closedBy")
  void shouldThrowWhenClosedWithoutAuditFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Period(
                TENANT,
                MAY_2026,
                PeriodStatus.CLOSED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  @DisplayName("CLOSED with audit fields is valid")
  void shouldConstructWhenClosedWithAuditFields() {
    Period p =
        new Period(
            TENANT,
            MAY_2026,
            PeriodStatus.CLOSED,
            Optional.of(Instant.parse("2026-06-01T09:00:00Z")),
            Optional.of("system"),
            Optional.empty(),
            Optional.empty());
    assertEquals(PeriodStatus.CLOSED, p.status());
    assertEquals("system", p.closedBy().orElseThrow());
  }

  @Test
  @DisplayName("openFor factory returns an OPEN period with no audit fields")
  void shouldReturnOpenFromFactory() {
    Period p = Period.openFor(TENANT, MAY_2026);
    assertEquals(PeriodStatus.OPEN, p.status());
    assertEquals(MAY_2026, p.yearMonth());
    assertEquals(TENANT, p.tenantId());
    assertEquals(Optional.empty(), p.closedAt());
    assertEquals(Optional.empty(), p.closedBy());
    assertEquals(Optional.empty(), p.reopenedAt());
    assertEquals(Optional.empty(), p.reopenedBy());
  }
}
