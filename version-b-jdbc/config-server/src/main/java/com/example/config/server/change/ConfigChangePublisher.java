package com.example.config.server.change;

/**
 * Single fan-out point from any backend's change detector onto Spring Cloud Bus.
 *
 * <p>This is the seam that keeps client applications byte-identical across all three backends: a
 * detector only has to say "these applications changed", and everything downstream — the Bus, the
 * clients, the snapshot swap — is shared.
 */
public interface ConfigChangePublisher {

  void publish(ConfigChangeNotification notification);
}
