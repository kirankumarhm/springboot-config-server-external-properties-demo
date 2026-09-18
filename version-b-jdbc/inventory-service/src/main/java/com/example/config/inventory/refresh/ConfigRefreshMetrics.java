package com.example.config.inventory.refresh;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Micrometer instrumentation for configuration refresh. */
@Component
public class ConfigRefreshMetrics {

  private final MeterRegistry registry;

  public ConfigRefreshMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordAttempt(String application, String trigger) {
    Counter.builder("config.refresh.attempts")
        .tag("application", application)
        .tag("trigger", trigger)
        .register(this.registry)
        .increment();
  }

  public void recordOutcome(String application, String trigger, String outcome, long startNanos) {
    Counter.builder("config.refresh.outcome")
        .tag("application", application)
        .tag("trigger", trigger)
        .tag("outcome", outcome)
        .register(this.registry)
        .increment();
    Timer.builder("config.refresh.duration")
        .tag("application", application)
        .tag("outcome", outcome)
        .register(this.registry)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  public void recordFailure(String application, String trigger, String reason, long startNanos) {
    Counter.builder("config.refresh.failures")
        .tag("application", application)
        .tag("reason", reason)
        .register(this.registry)
        .increment();
    recordOutcome(application, trigger, ConfigSnapshotStatus.REJECTED, startNanos);
  }

  /** Registers the snapshot version and staleness gauges once, at provider startup. */
  public void bindGauges(String application, ConfigurationSnapshotProvider<?> provider) {
    Gauge.builder("config.snapshot.version", provider, p -> p.status().version())
        .tag("application", application)
        .description("Current configuration snapshot version")
        .register(this.registry);
    Gauge.builder(
            "config.refresh.age.seconds",
            provider,
            p -> Duration.between(p.status().appliedAt(), Instant.now()).toSeconds())
        .tag("application", application)
        .description("Seconds since the current snapshot was applied")
        .register(this.registry);
  }
}
