package com.example.config.server.change;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.bus.event.RefreshRemoteApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes a refresh broadcast for each affected application.
 *
 * <p>Publishes the event in-process rather than posting to this server's own {@code /monitor}
 * endpoint. {@code /monitor} exists because Git hosting providers need an HTTP entry point; a
 * database or bucket detector already runs inside the server, so routing through HTTP would only
 * add a hop, force the server to authenticate to itself, and drag in webhook signature validation.
 *
 * <p>The destination is a bare application name (or {@code "*"}), matching exactly what {@code
 * PropertyPathEndpoint} emits on the Git path, so both backends produce identical bus traffic and
 * the clients cannot tell them apart.
 */
@Component
public class BusConfigChangePublisher implements ConfigChangePublisher {

  private static final Logger log = LoggerFactory.getLogger(BusConfigChangePublisher.class);

  private final ApplicationEventPublisher events;
  private final MeterRegistry registry;
  private final String busId;

  public BusConfigChangePublisher(
      ApplicationEventPublisher events,
      MeterRegistry registry,
      @Value("${spring.cloud.bus.id:config-server}") String busId) {
    this.events = events;
    this.registry = registry;
    this.busId = busId;
  }

  @Override
  public void publish(ConfigChangeNotification notification) {
    for (String destination : notification.applications()) {
      this.events.publishEvent(new RefreshRemoteApplicationEvent(this, this.busId, destination));
      Counter.builder("config.change.broadcast")
          .tag("source", notification.source())
          .tag("destination", destination)
          .register(this.registry)
          .increment();
      log.info(
          "{\"event\":\"config.change.broadcast\",\"source\":\"{}\",\"destination\":\"{}\","
              + "\"correlationId\":\"{}\",\"detectedAt\":\"{}\"}",
          notification.source(),
          destination,
          notification.correlationId(),
          notification.detectedAt());
    }
  }
}
