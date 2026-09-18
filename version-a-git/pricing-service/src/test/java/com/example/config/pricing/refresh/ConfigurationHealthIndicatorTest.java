package com.example.config.pricing.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Verifies the health contract, including the deliberate choice not to report DOWN on rejection.
 */
class ConfigurationHealthIndicatorTest {

  private static ConfigurationSnapshotProvider<String> provider(
      String snapshot, String outcome, String failureReason, long rejected) {
    return new ConfigurationSnapshotProvider<>() {
      @Override
      public String get() {
        return snapshot;
      }

      @Override
      public ConfigSnapshotStatus status() {
        return new ConfigSnapshotStatus(
            "pricing-service",
            4L,
            Instant.parse("2026-08-30T10:00:00Z"),
            outcome,
            failureReason,
            9L,
            rejected,
            List.of("pricing.discount-percentage"));
      }
    };
  }

  @Test
  @DisplayName("UP with snapshot details when configuration has been applied")
  void upWhenApplied() {
    Health health =
        new ConfigurationHealthIndicator(provider("snap", ConfigSnapshotStatus.APPLIED, null, 0L))
            .health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("application", "pricing-service")
        .containsEntry("snapshotVersion", 4L)
        .containsEntry("lastOutcome", ConfigSnapshotStatus.APPLIED)
        .containsEntry("refreshAttempts", 9L)
        .containsEntry("lastChangedKeys", List.of("pricing.discount-percentage"));
    assertThat(health.getDetails()).doesNotContainKey("lastFailureReason");
  }

  @Test
  @DisplayName("a REJECTED refresh stays UP: last-known-good is still being served")
  void staysUpWhenRefreshRejected() {
    Health health =
        new ConfigurationHealthIndicator(
                provider(
                    "snap",
                    ConfigSnapshotStatus.REJECTED,
                    "discountPercentage must be <= 90.0",
                    3L))
            .health();

    // Reporting DOWN here would pull a healthy instance out of a load balancer for a problem
    // that lives in the configuration store, not in this service.
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("lastOutcome", ConfigSnapshotStatus.REJECTED)
        .containsEntry("rejectedCount", 3L)
        .containsEntry("lastFailureReason", "discountPercentage must be <= 90.0");
  }

  @Test
  @DisplayName("DOWN only when no snapshot could ever be built")
  void downWhenNoSnapshot() {
    Health health = new ConfigurationHealthIndicator(provider(null, "STARTING", null, 0L)).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }
}
