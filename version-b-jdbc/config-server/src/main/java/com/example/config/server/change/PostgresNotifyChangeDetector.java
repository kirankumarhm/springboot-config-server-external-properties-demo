package com.example.config.server.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Detects configuration changes by listening on the PostgreSQL {@code config_changed} channel.
 *
 * <p>Because the notification is raised by a trigger, any writer is covered — application, admin
 * tool, or a DBA in psql — and because {@code NOTIFY} is transactional, a rolled-back change never
 * broadcasts.
 */
@Component
@ConfigurationProperties(prefix = "app.config-change.listener")
public class PostgresNotifyChangeDetector implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PostgresNotifyChangeDetector.class);

  /** Identifies the dedicated LISTEN session in pg_stat_activity. */
  public static final String LISTENER_APPLICATION_NAME = "config-notify-listener";

  private final String jdbcUrl;
  private final String username;
  private final String password;
  private final ConfigChangePublisher publisher;
  private final JdbcRevisionPollingDetector reconciler;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final AtomicReference<Instant> lastNotification = new AtomicReference<>();
  private final AtomicLong receivedCount = new AtomicLong();
  private final AtomicLong dropCount = new AtomicLong();
  private volatile boolean connected;
  private volatile boolean running;
  private Thread worker;

  private String channel = "config_changed";
  private long pollTimeoutMillis = 10_000L;
  private long initialBackoffMillis = 1_000L;
  private long maxBackoffMillis = 30_000L;

  public PostgresNotifyChangeDetector(
      @Value("${spring.datasource.url}") String jdbcUrl,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password,
      ConfigChangePublisher publisher,
      JdbcRevisionPollingDetector reconciler) {
    this.jdbcUrl = jdbcUrl;
    this.username = username;
    this.password = password;
    this.publisher = publisher;
    this.reconciler = reconciler;
  }

  @Override
  public void start() {
    this.running = true;
    this.worker = new Thread(this::listenLoop, "pg-config-listener");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  @Override
  public void stop() {
    this.running = false;
    if (this.worker != null) {
      this.worker.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return this.running;
  }

  private void listenLoop() {
    long backoff = this.initialBackoffMillis;
    while (this.running) {
      // One raw, dedicated connection - NOT from a pool. LISTEN binds the subscription to this
      // session for its whole lifetime, so the connection can never be handed back.
      //
      // ApplicationName is set so this session is distinguishable from the main read pool in
      // pg_stat_activity. That matters operationally (an idle-looking backend that must never be
      // reaped) and it makes the connection precisely targetable when testing recovery.
      Properties connectionProps = new Properties();
      connectionProps.setProperty("user", this.username);
      connectionProps.setProperty("password", this.password);
      connectionProps.setProperty("ApplicationName", LISTENER_APPLICATION_NAME);

      try (Connection connection = DriverManager.getConnection(this.jdbcUrl, connectionProps)) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + this.channel);
        }
        this.connected = true;
        backoff = this.initialBackoffMillis;
        log.info("Listening on PostgreSQL channel '{}'", this.channel);

        // A change committed while this listener was disconnected is gone for good: NOTIFY only
        // reaches listeners that are connected at commit time. Reconcile immediately on (re)connect
        // so nothing is silently missed across a restart or a dropped socket.
        this.reconciler.reconcileNow("listener-connect");

        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        while (this.running) {
          PGNotification[] notifications =
              pgConnection.getNotifications((int) this.pollTimeoutMillis);
          if (notifications != null) {
            for (PGNotification notification : notifications) {
              handle(notification.getParameter());
            }
          }
        }
      } catch (SQLException ex) {
        this.connected = false;
        this.dropCount.incrementAndGet();
        if (!this.running) {
          return;
        }
        log.warn(
            "PostgreSQL notification listener lost its connection ({}); reconnecting in {}ms",
            ex.getMessage(),
            backoff);
        try {
          Thread.sleep(backoff);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        backoff = Math.min(backoff * 2, this.maxBackoffMillis);
      }
    }
  }

  private void handle(String payload) {
    try {
      JsonNode node = this.objectMapper.readTree(payload);
      String application = node.path("application").asText();
      if (application.isEmpty()) {
        return;
      }
      // Shared configuration lives under the literal application name 'application';
      // a change there must reach every client, matching the Git backend's "*" mapping.
      String destination = "application".equals(application) ? "*" : application;

      this.receivedCount.incrementAndGet();
      this.lastNotification.set(Instant.now());
      this.reconciler.noteObservedRevision(application, node.path("revision").asLong(0L));

      this.publisher.publish(
          new ConfigChangeNotification(
              Set.of(destination),
              "pg-notify",
              node.path("correlationId").asText(""),
              Instant.now()));
    } catch (Exception ex) {
      log.error("Could not handle notification payload '{}': {}", payload, ex.getMessage());
    }
  }

  public boolean isConnected() {
    return this.connected;
  }

  public Instant lastNotificationAt() {
    return this.lastNotification.get();
  }

  public long receivedCount() {
    return this.receivedCount.get();
  }

  public long dropCount() {
    return this.dropCount.get();
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public void setPollTimeoutMillis(long pollTimeoutMillis) {
    this.pollTimeoutMillis = pollTimeoutMillis;
  }

  public void setInitialBackoffMillis(long initialBackoffMillis) {
    this.initialBackoffMillis = initialBackoffMillis;
  }

  public void setMaxBackoffMillis(long maxBackoffMillis) {
    this.maxBackoffMillis = maxBackoffMillis;
  }
}
