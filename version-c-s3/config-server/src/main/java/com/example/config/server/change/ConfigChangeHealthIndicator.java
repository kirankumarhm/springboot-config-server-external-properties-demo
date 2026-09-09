package com.example.config.server.change;

import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Surfaces S3/SQS change-detection state under {@code /actuator/health}. */
@Component
public class ConfigChangeHealthIndicator implements HealthIndicator {

  private final S3EventSqsChangeDetector detector;

  public ConfigChangeHealthIndicator(S3EventSqsChangeDetector detector) {
    this.detector = detector;
  }

  @Override
  public Health health() {
    Instant last = this.detector.lastEventAt();
    return Health.up()
        .withDetail("mechanism", "S3 Event Notifications -> SQS")
        .withDetail("eventsReceived", this.detector.eventsReceived())
        .withDetail("recordsIgnored", this.detector.recordsIgnored())
        .withDetail("lastEventAt", last == null ? "never" : last.toString())
        .withDetail("deliverySemantics", "at-least-once, unordered; refresh is idempotent")
        .build();
  }
}
