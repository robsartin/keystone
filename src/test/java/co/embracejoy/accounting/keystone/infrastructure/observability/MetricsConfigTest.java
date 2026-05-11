package co.embracejoy.accounting.keystone.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricsConfig")
class MetricsConfigTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final MetricsConfig config = new MetricsConfig();

  @Test
  @DisplayName("registers keystone_journal_entries_posted_total counter for ok and invalid")
  void shouldRegisterCounters() {
    Counter ok = config.journalEntriesPostedOk(registry);
    Counter invalid = config.journalEntriesPostedInvalid(registry);

    ok.increment();
    invalid.increment(2);

    assertThat(
            registry
                .get("keystone_journal_entries_posted_total")
                .tag("result", "ok")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("keystone_journal_entries_posted_total")
                .tag("result", "invalid")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("registers keystone_journal_entries_post_duration timer")
  void shouldRegisterTimer() {
    Timer timer = config.journalEntriesPostDuration(registry);

    timer.record(java.time.Duration.ofMillis(15));

    assertThat(registry.get("keystone_journal_entries_post_duration").timer().count())
        .isEqualTo(1L);
  }
}
