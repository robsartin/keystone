package co.embracejoy.accounting.keystone.infrastructure.persistence.period;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.period.Period;
import co.embracejoy.accounting.keystone.domain.period.PeriodStatus;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@Transactional
@DisplayName("PeriodRepositoryAdapter (integration)")
class PeriodRepositoryAdapterIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired PeriodRepositoryAdapter repository;

  private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);
  private static final YearMonth JUNE_2026 = YearMonth.of(2026, 6);
  private static final YearMonth JULY_2026 = YearMonth.of(2026, 7);
  private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");

  private static Period closedPeriod(YearMonth ym) {
    return new Period(
        ym,
        PeriodStatus.CLOSED,
        Optional.of(NOW),
        Optional.of("system"),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  @DisplayName("save persists a new period and findByYearMonth reads it back")
  void shouldRoundTripWhenSavingAndReadingBack() {
    Period p = Period.openFor(MAY_2026);
    repository.save(p);

    Optional<Period> found = repository.findByYearMonth(MAY_2026);
    assertThat(found).isPresent();
    assertThat(found.get().yearMonth()).isEqualTo(MAY_2026);
    assertThat(found.get().status()).isEqualTo(PeriodStatus.OPEN);
  }

  @Test
  @DisplayName("findByYearMonth returns empty when no row exists")
  void shouldReturnEmptyWhenNoRowForYearMonth() {
    Optional<Period> found = repository.findByYearMonth(MAY_2026);
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("update from OPEN to CLOSED preserves audit fields")
  void shouldUpdateExistingPeriod() {
    Period open = Period.openFor(MAY_2026);
    repository.save(open);

    Period closed = closedPeriod(MAY_2026);
    repository.update(closed);

    Optional<Period> found = repository.findByYearMonth(MAY_2026);
    assertThat(found).isPresent();
    assertThat(found.get().status()).isEqualTo(PeriodStatus.CLOSED);
    assertThat(found.get().closedBy()).contains("system");
    assertThat(found.get().closedAt()).contains(NOW);
  }

  @Test
  @DisplayName("findAllClosed returns CLOSED rows in descending YearMonth order")
  void shouldReturnAllClosedDescending() {
    repository.save(closedPeriod(MAY_2026));
    repository.save(closedPeriod(JUNE_2026));
    repository.save(closedPeriod(JULY_2026));

    List<Period> closed = repository.findAllClosed();
    assertThat(closed).hasSize(3);
    assertThat(closed.get(0).yearMonth()).isEqualTo(JULY_2026);
    assertThat(closed.get(1).yearMonth()).isEqualTo(JUNE_2026);
    assertThat(closed.get(2).yearMonth()).isEqualTo(MAY_2026);
  }

  @Test
  @DisplayName("findLatestClosed returns the period with the maximum YearMonth")
  void shouldReturnLatestClosed() {
    repository.save(closedPeriod(MAY_2026));
    repository.save(closedPeriod(JUNE_2026));
    repository.save(closedPeriod(JULY_2026));

    Optional<Period> latest = repository.findLatestClosed();
    assertThat(latest).isPresent();
    assertThat(latest.get().yearMonth()).isEqualTo(JULY_2026);
  }
}
