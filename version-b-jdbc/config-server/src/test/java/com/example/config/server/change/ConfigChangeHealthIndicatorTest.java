package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Verifies that a degraded notification path is visible but does NOT report the server DOWN. */
@ExtendWith(MockitoExtension.class)
class ConfigChangeHealthIndicatorTest {

  @Mock private PostgresNotifyChangeDetector listener;
  @Mock private JdbcRevisionPollingDetector poller;

  @Test
  @DisplayName("connected listener reports push mode")
  void reportsPushModeWhenConnected() {
    given(this.listener.isConnected()).willReturn(true);
    given(this.listener.receivedCount()).willReturn(7L);
    given(this.listener.dropCount()).willReturn(0L);
    given(this.listener.lastNotificationAt()).willReturn(Instant.parse("2026-08-30T10:00:00Z"));
    given(this.poller.lastReconcileAt()).willReturn(Instant.parse("2026-08-30T10:00:05Z"));
    given(this.poller.reconcileBroadcasts()).willReturn(0L);

    Health health = new ConfigChangeHealthIndicator(this.listener, this.poller).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("listenerConnected", true)
        .containsEntry("notificationsReceived", 7L)
        .containsEntry("propagationMode", "push (sub-second)");
  }

  @Test
  @DisplayName("a dropped listener stays UP but is reported as degraded, not broken")
  void degradedButUpWhenListenerDropped() {
    given(this.listener.isConnected()).willReturn(false);
    given(this.listener.receivedCount()).willReturn(3L);
    given(this.listener.dropCount()).willReturn(2L);
    given(this.listener.lastNotificationAt()).willReturn(null);
    given(this.poller.lastReconcileAt()).willReturn(null);
    given(this.poller.reconcileBroadcasts()).willReturn(1L);

    Health health = new ConfigChangeHealthIndicator(this.listener, this.poller).health();

    // Configuration is still served correctly and the poller still propagates changes, just
    // more slowly. DOWN would remove a working Config Server from a load balancer.
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("listenerConnected", false)
        .containsEntry("listenerDrops", 2L)
        .containsEntry("propagationMode", "degraded: poller only")
        .containsEntry("lastNotificationAt", "never")
        .containsEntry("lastReconcileAt", "never");
  }
}
