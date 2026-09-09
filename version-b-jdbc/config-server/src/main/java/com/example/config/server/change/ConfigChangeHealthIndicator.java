package com.example.config.server.change;

import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces change-detection state.
 *
 * <p>A dropped listener connection does NOT make the server unhealthy: it still serves
 * configuration correctly, and the revision poller still propagates changes at a longer latency.
 * Reporting DOWN would remove a working Config Server from a load balancer over a degraded — not
 * broken — notification path. The degradation is visible in the details instead.
 */
@Component
public class ConfigChangeHealthIndicator implements HealthIndicator {

  private final PostgresNotifyChangeDetector listener;
  private final JdbcRevisionPollingDetector poller;

  public ConfigChangeHealthIndicator(
      PostgresNotifyChangeDetector listener, JdbcRevisionPollingDetector poller) {
    this.listener = listener;
    this.poller = poller;
  }

  @Override
  public Health health() {
    Instant lastNotification = this.listener.lastNotificationAt();
    Instant lastReconcile = this.poller.lastReconcileAt();

    return Health.up()
        .withDetail("mechanism", "postgres LISTEN/NOTIFY + revision poller")
        .withDetail("listenerConnected", this.listener.isConnected())
        .withDetail("notificationsReceived", this.listener.receivedCount())
        .withDetail("listenerDrops", this.listener.dropCount())
        .withDetail(
            "lastNotificationAt", lastNotification == null ? "never" : lastNotification.toString())
        .withDetail("lastReconcileAt", lastReconcile == null ? "never" : lastReconcile.toString())
        .withDetail("reconcileBroadcasts", this.poller.reconcileBroadcasts())
        .withDetail(
            "propagationMode",
            this.listener.isConnected() ? "push (sub-second)" : "degraded: poller only")
        .build();
  }
}
