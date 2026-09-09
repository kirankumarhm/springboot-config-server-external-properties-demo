package com.example.config.commons;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Template for the validate &rarr; compare &rarr; atomically swap &rarr; audit refresh algorithm.
 *
 * <p>Three deliberate design decisions, each forced by how Spring Cloud actually behaves:
 *
 * <ol>
 *   <li><b>Listens on {@link RefreshScopeRefreshedEvent}, not {@code EnvironmentChangeEvent}.</b>
 *       {@code ConfigurationPropertiesRebinder} listens on the latter, and two listeners on one
 *       event have no guaranteed relative order, so reading the properties bean from an {@code
 *       EnvironmentChangeEvent} handler can observe pre-rebind values. {@code
 *       RefreshScopeRefreshedEvent} is published after rebinding completes.
 *   <li><b>Validates here rather than with {@code @Validated} on the properties class.</b> {@code
 *       ConfigurationPropertiesRebinder.rebind} rethrows after recording the error, so a constraint
 *       violation during rebind propagates out of {@code ContextRefresher.refresh()} and {@code
 *       RefreshScopeRefreshedEvent} is never published at all. The refresh would fail silently with
 *       no chance to retain last-known-good or report the failure. Validating the already-rebound
 *       bean here keeps the refresh chain intact and makes rejection observable.
 *   <li><b>Publishes through an {@link AtomicReference}.</b> The rebinder mutates the properties
 *       bean in place, field by field; its own Javadoc warns that concurrent readers can observe
 *       transient intermediate state. Readers here take one reference load and therefore always see
 *       a complete snapshot.
 * </ol>
 *
 * @param <T> immutable snapshot type, normally a record so that {@code equals} gives the no-op test
 */
public abstract class AbstractConfigurationSnapshotProvider<T>
    implements ConfigurationSnapshotProvider<T>, ApplicationListener<RefreshScopeRefreshedEvent> {

  private static final Logger log =
      LoggerFactory.getLogger(AbstractConfigurationSnapshotProvider.class);

  private final AtomicReference<T> snapshot = new AtomicReference<>();
  private final AtomicReference<Instant> appliedAt = new AtomicReference<>(Instant.EPOCH);
  private final AtomicReference<String> outcome = new AtomicReference<>("STARTING");
  private final AtomicReference<String> failureReason = new AtomicReference<>();
  private final AtomicLong version = new AtomicLong();
  private final AtomicLong attempts = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  private final Validator validator;
  private final ConfigRefreshMetrics metrics;
  private final ConfigRefreshAuditor auditor;
  private final EnvironmentChangeKeyRecorder keyRecorder;

  protected AbstractConfigurationSnapshotProvider(
      Validator validator,
      ConfigRefreshMetrics metrics,
      ConfigRefreshAuditor auditor,
      EnvironmentChangeKeyRecorder keyRecorder) {
    this.validator = validator;
    this.metrics = metrics;
    this.auditor = auditor;
    this.keyRecorder = keyRecorder;
  }

  /** Owning application name, used for tagging and audit. */
  protected abstract String applicationName();

  /**
   * Every setter-bound {@code @ConfigurationProperties} bean that feeds this snapshot. All are
   * validated together, so a violation in shared configuration rejects the refresh just as an
   * application-specific one does.
   */
  protected abstract List<Object> propertiesBeans();

  /** Builds an immutable snapshot from the current state of the properties bean. */
  protected abstract T buildSnapshot();

  @PostConstruct
  void initialise() {
    String violations = validate();
    if (violations != null) {
      // Fail fast: a service must not start and serve traffic on invalid configuration.
      throw new ConfigurationValidationException(
          "Invalid configuration for " + applicationName() + " at startup: " + violations);
    }
    this.snapshot.set(buildSnapshot());
    this.version.set(1L);
    this.appliedAt.set(Instant.now());
    this.outcome.set(ConfigSnapshotStatus.APPLIED);
    this.metrics.bindGauges(applicationName(), this);
    this.auditor.record(
        applicationName(), "startup", ConfigSnapshotStatus.APPLIED, List.of(), 1L, null);
    log.info(
        "Initial configuration snapshot applied for {}: {}",
        applicationName(),
        this.snapshot.get());
  }

  @Override
  public T get() {
    return this.snapshot.get();
  }

  @Override
  public ConfigSnapshotStatus status() {
    return new ConfigSnapshotStatus(
        applicationName(),
        this.version.get(),
        this.appliedAt.get(),
        this.outcome.get(),
        this.failureReason.get(),
        this.attempts.get(),
        this.rejected.get(),
        this.keyRecorder.lastChangedKeys());
  }

  @Override
  public void onApplicationEvent(RefreshScopeRefreshedEvent event) {
    applyRefresh("refresh");
  }

  /** Runs the refresh algorithm. Visible for tests and for a manual trigger. */
  public void applyRefresh(String trigger) {
    long startNanos = System.nanoTime();
    this.attempts.incrementAndGet();
    this.metrics.recordAttempt(applicationName(), trigger);
    List<String> changedKeys = this.keyRecorder.lastChangedKeys();

    String violations = validate();
    if (violations != null) {
      // Last-known-good retained. The properties bean now holds invalid values, but nothing
      // reads it directly, so the service keeps serving the previous valid snapshot.
      this.rejected.incrementAndGet();
      this.outcome.set(ConfigSnapshotStatus.REJECTED);
      this.failureReason.set(violations);
      this.metrics.recordFailure(applicationName(), trigger, "validation", startNanos);
      this.auditor.record(
          applicationName(),
          trigger,
          ConfigSnapshotStatus.REJECTED,
          changedKeys,
          this.version.get(),
          violations);
      log.error(
          "Rejected configuration refresh for {}; retaining snapshot version {}: {}",
          applicationName(),
          this.version.get(),
          violations);
      return;
    }

    T candidate = buildSnapshot();
    if (candidate.equals(this.snapshot.get())) {
      // No effective change: do not bump the version, do not churn downstream observers.
      this.outcome.set(ConfigSnapshotStatus.NO_CHANGE);
      this.failureReason.set(null);
      this.metrics.recordOutcome(
          applicationName(), trigger, ConfigSnapshotStatus.NO_CHANGE, startNanos);
      this.auditor.record(
          applicationName(),
          trigger,
          ConfigSnapshotStatus.NO_CHANGE,
          changedKeys,
          this.version.get(),
          null);
      return;
    }

    this.snapshot.set(candidate);
    long newVersion = this.version.incrementAndGet();
    this.appliedAt.set(Instant.now());
    this.outcome.set(ConfigSnapshotStatus.APPLIED);
    this.failureReason.set(null);
    this.metrics.recordOutcome(
        applicationName(), trigger, ConfigSnapshotStatus.APPLIED, startNanos);
    this.auditor.record(
        applicationName(), trigger, ConfigSnapshotStatus.APPLIED, changedKeys, newVersion, null);
    log.info(
        "Applied configuration snapshot version {} for {} (changed keys: {})",
        newVersion,
        applicationName(),
        changedKeys);
  }

  private String validate() {
    String message =
        propertiesBeans().stream()
            .map(this.validator::validate)
            .flatMap(Set::stream)
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return message.isEmpty() ? null : message;
  }
}
