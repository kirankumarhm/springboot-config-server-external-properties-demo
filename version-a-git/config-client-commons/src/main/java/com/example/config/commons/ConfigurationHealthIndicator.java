package com.example.config.commons;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports configuration state under {@code /actuator/health}.
 *
 * <p>A rejected refresh does NOT make the service unhealthy: it is still serving valid
 * last-known-good configuration, so reporting DOWN would take a working instance out of a load
 * balancer for a problem that lives in the config repository. It surfaces as UP with an explicit
 * {@code lastOutcome=REJECTED} plus the reason, which is what an operator needs to see.
 */
@Component
public class ConfigurationHealthIndicator implements HealthIndicator {

  private final ConfigurationSnapshotProvider<?> provider;

  public ConfigurationHealthIndicator(ConfigurationSnapshotProvider<?> provider) {
    this.provider = provider;
  }

  @Override
  public Health health() {
    ConfigSnapshotStatus status = this.provider.status();
    Health.Builder builder = (this.provider.get() == null) ? Health.down() : Health.up();

    builder
        .withDetail("application", status.application())
        .withDetail("snapshotVersion", status.version())
        .withDetail("appliedAt", status.appliedAt().toString())
        .withDetail("lastOutcome", status.lastOutcome())
        .withDetail("refreshAttempts", status.refreshAttempts())
        .withDetail("rejectedCount", status.rejectedCount())
        .withDetail("lastChangedKeys", status.lastChangedKeys());

    if (status.lastFailureReason() != null) {
      builder.withDetail("lastFailureReason", status.lastFailureReason());
    }
    return builder.build();
  }
}
