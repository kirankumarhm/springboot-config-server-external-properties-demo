package com.example.config.inventory.refresh;

/**
 * Hands out the currently effective, immutable configuration snapshot.
 *
 * <p>This is the only sanctioned way for business code to read refreshable configuration. Injecting
 * a {@code @ConfigurationProperties} bean directly is unsafe during a refresh: Spring Cloud's
 * {@code ConfigurationPropertiesRebinder} mutates that bean in place, field by field, so a
 * concurrent reader can observe a half-applied state. Reading through this provider is a single
 * {@code AtomicReference} load, so a caller always sees one complete snapshot.
 *
 * @param <T> immutable snapshot type, normally a record
 */
public interface ConfigurationSnapshotProvider<T> {

  /**
   * The current snapshot. Never {@code null} once the application has started, and never a
   * partially-applied set of values.
   */
  T get();

  /** Metadata about the snapshot: version, when it was applied, and the last refresh outcome. */
  ConfigSnapshotStatus status();
}
