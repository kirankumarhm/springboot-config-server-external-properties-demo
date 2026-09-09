package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.bus.event.RefreshRemoteApplicationEvent;

/**
 * Verifies the single fan-out point every backend converges on.
 *
 * <p>The destination format matters: it must be a bare application name (or {@code "*"}), exactly
 * what the Git path's {@code PropertyPathEndpoint} emits, so clients cannot tell the backends
 * apart.
 */
class BusConfigChangePublisherTest {

  private final List<Object> events = new ArrayList<>();

  private BusConfigChangePublisher publisher() {
    return new BusConfigChangePublisher(
        this.events::add, new SimpleMeterRegistry(), "config-server:8888:abc");
  }

  private static ConfigChangeNotification notification(String... applications) {
    Set<String> apps = new LinkedHashSet<>(List.of(applications));
    return new ConfigChangeNotification(apps, "test-source", "corr-1", Instant.now());
  }

  @Test
  @DisplayName("publishes one bus event per affected application")
  void publishesOneEventPerApplication() {
    publisher().publish(notification("inventory-service", "pricing-service"));

    assertThat(this.events).hasSize(2);
    assertThat(this.events).allMatch(RefreshRemoteApplicationEvent.class::isInstance);
  }

  @Test
  @DisplayName("a bare application name is normalised by the Bus to 'app:**' (all instances)")
  void destinationIsNormalisedToAllInstances() {
    publisher().publish(notification("inventory-service"));

    // PathDestinationFactory appends ":**" to any destination with at most one colon, so a bare
    // application name automatically targets EVERY instance of that application. This is also why
    // a prefix such as "inventory" does not match "inventory-service": it becomes "inventory:**".
    RefreshRemoteApplicationEvent event = (RefreshRemoteApplicationEvent) this.events.get(0);
    assertThat(event.getDestinationService()).isEqualTo("inventory-service:**");
    assertThat(event.getOriginService()).isEqualTo("config-server:8888:abc");
  }

  @Test
  @DisplayName("a wildcard destination is passed through unchanged")
  void wildcardIsPassedThrough() {
    publisher().publish(notification("*"));

    assertThat(((RefreshRemoteApplicationEvent) this.events.get(0)).getDestinationService())
        .isEqualTo("*:**");
  }

  @Test
  @DisplayName("an empty notification publishes nothing")
  void emptyNotificationPublishesNothing() {
    publisher().publish(notification());

    assertThat(this.events).isEmpty();
  }
}
