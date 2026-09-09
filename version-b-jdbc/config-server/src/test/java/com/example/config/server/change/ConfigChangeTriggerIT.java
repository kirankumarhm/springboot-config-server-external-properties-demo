package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the SQL that drives version B's change detection.
 *
 * <p>This covers the part of the system that no unit test can reach and that would otherwise only
 * be exercised by a shell script against a running stack: the Flyway migrations, the history and
 * revision triggers, and the transactional semantics of {@code pg_notify}. It runs against a real
 * PostgreSQL container, because the behaviour being asserted (statement-level triggers with
 * transition tables, NOTIFY delivered only on COMMIT) does not exist in an in-memory database.
 */
@Testcontainers
class ConfigChangeTriggerIT {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.6")
          .withDatabaseName("configdb")
          .withUsername("config_admin")
          .withPassword("config-secret");

  private static String jdbcUrl;

  @BeforeAll
  static void applyMigrations() throws Exception {
    jdbcUrl = POSTGRES.getJdbcUrl();
    try (Connection connection = connect(null);
        Statement statement = connection.createStatement()) {
      for (String migration : List.of("V1__config_schema.sql", "V2__config_change_notify.sql")) {
        String sql =
            new String(
                new ClassPathResource("db/migration/" + migration).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        statement.execute(sql);
      }
    }
  }

  private static Connection connect(String applicationName) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", POSTGRES.getUsername());
    props.setProperty("password", POSTGRES.getPassword());
    if (applicationName != null) {
      props.setProperty("ApplicationName", applicationName);
    }
    return DriverManager.getConnection(jdbcUrl, props);
  }

  @BeforeEach
  void resetBaseline() throws Exception {
    try (Connection c = connect(null);
        Statement s = c.createStatement()) {
      s.execute(
          "UPDATE properties SET \"value\"='500' "
              + "WHERE application='inventory-service' AND \"key\"='inventory.max-order-quantity'");
    }
  }

  @Test
  @DisplayName("migrations create the schema, constraints and seed data")
  void migrationsCreateSchema() throws Exception {
    try (Connection c = connect(null);
        Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("SELECT count(*) FROM properties")) {
        rs.next();
        assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(10);
      }
      // Profile-independent rows must be real NULLs, or the shipped
      // "PROFILE is null" query never finds them.
      try (ResultSet rs = s.executeQuery("SELECT count(*) FROM properties WHERE profile IS NULL")) {
        rs.next();
        assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(10);
      }
      try (ResultSet rs =
          s.executeQuery(
              "SELECT count(*) FROM information_schema.triggers "
                  + "WHERE trigger_name LIKE 'trg_notify_config%'")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(3);
      }
    }
  }

  @Test
  @DisplayName("UNIQUE NULLS NOT DISTINCT rejects a duplicate profile-independent key")
  void uniqueConstraintCoversNullProfiles() throws Exception {
    try (Connection c = connect(null);
        Statement s = c.createStatement()) {
      // With a plain UNIQUE, NULLs are distinct and this insert would silently succeed,
      // leaving two rows for one key whose winner depends on physical order.
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () ->
                      s.execute(
                          "INSERT INTO properties (application, profile, label, \"key\", \"value\") "
                              + "VALUES ('inventory-service', NULL, 'main', "
                              + "'inventory.max-order-quantity', '999')")))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  @DisplayName("an UPDATE bumps config_revision and writes an audit row")
  void updateBumpsRevisionAndWritesHistory() throws Exception {
    long before = revisionOf("inventory-service");
    int historyBefore = historyCount();

    try (Connection c = connect(null);
        Statement s = c.createStatement()) {
      s.execute(
          "UPDATE properties SET \"value\"='750' "
              + "WHERE application='inventory-service' AND \"key\"='inventory.max-order-quantity'");
    }

    assertThat(revisionOf("inventory-service")).isGreaterThan(before);
    assertThat(historyCount()).isGreaterThan(historyBefore);

    try (Connection c = connect(null);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT operation, old_value, new_value, changed_by FROM properties_history "
                    + "ORDER BY history_id DESC LIMIT 1")) {
      rs.next();
      assertThat(rs.getString("operation")).isEqualTo("U");
      assertThat(rs.getString("old_value")).isEqualTo("500");
      assertThat(rs.getString("new_value")).isEqualTo("750");
      assertThat(rs.getString("changed_by")).isEqualTo("config_admin");
    }
  }

  @Test
  @DisplayName("NOTIFY carries the application name and reaches a LISTENing session")
  void notifyReachesListener() throws Exception {
    try (Connection listener = connect("config-notify-listener")) {
      try (Statement s = listener.createStatement()) {
        s.execute("LISTEN config_changed");
      }

      try (Connection writer = connect(null);
          Statement s = writer.createStatement()) {
        s.execute(
            "UPDATE properties SET \"value\"='800' "
                + "WHERE application='inventory-service' "
                + "AND \"key\"='inventory.max-order-quantity'");
      }

      List<String> payloads = drain(listener, 5000);
      assertThat(payloads).hasSize(1);
      assertThat(payloads.get(0)).contains("\"application\" : \"inventory-service\"");
    }
  }

  @Test
  @DisplayName("a ROLLED BACK change produces no notification at all - NOTIFY is transactional")
  void rolledBackChangeDoesNotNotify() throws Exception {
    try (Connection listener = connect("config-notify-listener")) {
      try (Statement s = listener.createStatement()) {
        s.execute("LISTEN config_changed");
      }

      try (Connection writer = connect(null)) {
        writer.setAutoCommit(false);
        try (Statement s = writer.createStatement()) {
          s.execute(
              "UPDATE properties SET \"value\"='7777' "
                  + "WHERE application='inventory-service' "
                  + "AND \"key\"='inventory.max-order-quantity'");
        }
        writer.rollback();
      }

      // This is the transactional-outbox guarantee, obtained without an outbox table.
      assertThat(drain(listener, 2500)).isEmpty();
    }
  }

  @Test
  @DisplayName("a bulk UPDATE of many rows produces exactly ONE notification")
  void bulkUpdateProducesOneNotification() throws Exception {
    try (Connection listener = connect("config-notify-listener")) {
      try (Statement s = listener.createStatement()) {
        s.execute("LISTEN config_changed");
      }

      try (Connection writer = connect(null);
          Statement s = writer.createStatement()) {
        s.execute("UPDATE properties SET updated_at=now() WHERE application='inventory-service'");
      }

      // Statement-level triggers with transition tables collapse this to one event; a row-level
      // trigger would emit one per row and storm the bus.
      assertThat(drain(listener, 5000)).hasSize(1);
    }
  }

  @Test
  @DisplayName("a change to shared configuration notifies under the 'application' name")
  void sharedConfigurationNotifies() throws Exception {
    try (Connection listener = connect("config-notify-listener")) {
      try (Statement s = listener.createStatement()) {
        s.execute("LISTEN config_changed");
      }

      try (Connection writer = connect(null);
          Statement s = writer.createStatement()) {
        s.execute(
            "UPDATE properties SET \"value\"='production-like' "
                + "WHERE application='application' AND \"key\"='demo.shared.environment-label'");
      }

      List<String> payloads = drain(listener, 5000);
      assertThat(payloads).hasSize(1);
      assertThat(payloads.get(0)).contains("\"application\" : \"application\"");
    }
  }

  private static List<String> drain(Connection listener, int timeoutMillis) throws SQLException {
    PGConnection pg = listener.unwrap(PGConnection.class);
    List<String> payloads = new ArrayList<>();
    PGNotification[] notifications = pg.getNotifications(timeoutMillis);
    if (notifications != null) {
      for (PGNotification notification : notifications) {
        payloads.add(notification.getParameter());
      }
    }
    return payloads;
  }

  private static long revisionOf(String application) throws SQLException {
    try (Connection c = connect(null);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT revision FROM config_revision WHERE application='" + application + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static int historyCount() throws SQLException {
    try (Connection c = connect(null);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM properties_history")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
