package com.example.config.server.change;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles client state against {@code config_revision}, as a safety net for the listener.
 *
 * <p>This is not redundancy — it closes gaps the listener cannot:
 *
 * <ul>
 *   <li>a change committed while the listener's socket was down is lost forever, because {@code
 *       NOTIFY} is delivered only to listeners connected at commit time;
 *   <li>the same applies across a server restart;
 *   <li>PostgreSQL's async notification queue is bounded and can drop under extreme write volume.
 * </ul>
 *
 * <p>Net effect: the SLA is met by {@code NOTIFY} at sub-second latency, and the worst case
 * degrades to the poll interval rather than to "never".
 */
@Component
public class JdbcRevisionPollingDetector {

  private static final Logger log = LoggerFactory.getLogger(JdbcRevisionPollingDetector.class);

  private final JdbcTemplate jdbc;
  private final ConfigChangePublisher publisher;
  private final Map<String, Long> seenRevisions = new ConcurrentHashMap<>();
  private volatile Instant lastReconcileAt;
  // AtomicLong, not a volatile long: `volatile` makes reads visible but does NOT make ++ atomic.
  // It is currently only safe because reconcileNow is synchronized, which is a coupling nobody
  // should have to remember. (Caught by SpotBugs VO_VOLATILE_INCREMENT.)
  private final AtomicLong reconcileBroadcasts = new AtomicLong();

  public JdbcRevisionPollingDetector(JdbcTemplate jdbc, ConfigChangePublisher publisher) {
    this.jdbc = jdbc;
    this.publisher = publisher;
  }

  @Scheduled(
      initialDelayString = "${app.config-change.revision-poller.initial-delay:15000}",
      fixedDelayString = "${app.config-change.revision-poller.interval:15000}")
  void poll() {
    reconcileNow("scheduled-poll");
  }

  /** Compares stored revisions against what has been observed, broadcasting on any drift. */
  public synchronized void reconcileNow(String source) {
    try {
      Map<String, Long> current = new HashMap<>();
      this.jdbc.query(
          "SELECT application, revision FROM config_revision",
          rs -> {
            current.put(rs.getString("application"), rs.getLong("revision"));
          });

      this.lastReconcileAt = Instant.now();

      for (Map.Entry<String, Long> entry : current.entrySet()) {
        String application = entry.getKey();
        long revision = entry.getValue();
        Long seen = this.seenRevisions.get(application);

        if (seen == null) {
          // First observation: adopt as the baseline. Broadcasting here would refresh every
          // client on every server restart for no reason.
          this.seenRevisions.put(application, revision);
          continue;
        }
        if (revision > seen) {
          this.seenRevisions.put(application, revision);
          this.reconcileBroadcasts.incrementAndGet();
          String destination = "application".equals(application) ? "*" : application;
          log.warn(
              "Revision drift for '{}' ({} -> {}) detected by {}; the listener missed a change",
              application,
              seen,
              revision,
              source);
          this.publisher.publish(
              new ConfigChangeNotification(
                  Set.of(destination),
                  "jdbc-poll",
                  UUID.randomUUID().toString().substring(0, 8),
                  Instant.now()));
        }
      }
    } catch (Exception ex) {
      log.error("Revision reconciliation failed: {}", ex.getMessage());
    }
  }

  /** Records a revision already handled via NOTIFY, so the poller does not broadcast it again. */
  public void noteObservedRevision(String application, long revision) {
    if (revision > 0) {
      this.seenRevisions.merge(application, revision, Math::max);
    }
  }

  public Instant lastReconcileAt() {
    return this.lastReconcileAt;
  }

  public long reconcileBroadcasts() {
    return this.reconcileBroadcasts.get();
  }
}
