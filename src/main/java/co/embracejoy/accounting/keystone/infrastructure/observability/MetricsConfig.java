package co.embracejoy.accounting.keystone.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

  public static final String COUNTER_POSTED = "keystone_journal_entries_posted_total";
  public static final String TIMER_POST_DURATION = "keystone_journal_entries_post_duration";

  @Bean
  public Counter journalEntriesPostedOk(MeterRegistry registry) {
    return Counter.builder(COUNTER_POSTED)
        .description("Journal entries successfully posted")
        .tag("result", "ok")
        .register(registry);
  }

  @Bean
  public Counter journalEntriesPostedInvalid(MeterRegistry registry) {
    return Counter.builder(COUNTER_POSTED)
        .description("Journal entries rejected for domain failures")
        .tag("result", "invalid")
        .register(registry);
  }

  @Bean
  public Timer journalEntriesPostDuration(MeterRegistry registry) {
    return Timer.builder(TIMER_POST_DURATION)
        .description("Wall-clock duration of POST /journal-entries")
        .register(registry);
  }
}
