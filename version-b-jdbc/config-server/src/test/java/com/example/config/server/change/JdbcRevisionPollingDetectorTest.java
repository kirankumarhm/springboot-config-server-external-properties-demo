package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Verifies the reconciler that closes the gaps LISTEN/NOTIFY structurally cannot.
 *
 * <p>The behaviour under test is subtle and easy to get wrong in both directions: it must broadcast
 * when a revision moved while nothing was listening, and it must NOT broadcast on first observation
 * or every server restart would refresh the whole estate for no reason.
 */
@ExtendWith(MockitoExtension.class)
// The shared @BeforeEach stub is replaced in containsDatabaseFailure(), which strict stubbing
// reports as unnecessary. Leniency is scoped to this one class rather than the whole build.
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcRevisionPollingDetectorTest {

  @Mock private JdbcTemplate jdbcTemplate;

  private final List<ConfigChangeNotification> published = new ArrayList<>();
  private final Map<String, Long> revisions = new LinkedHashMap<>();
  private JdbcRevisionPollingDetector detector;

  @BeforeEach
  void setUp() {
    this.published.clear();
    this.revisions.clear();
    this.detector = new JdbcRevisionPollingDetector(this.jdbcTemplate, this.published::add);

    willAnswer(
            invocation -> {
              RowCallbackHandler handler = invocation.getArgument(1);
              for (Map.Entry<String, Long> entry : this.revisions.entrySet()) {
                handler.processRow(row(entry.getKey(), entry.getValue()));
              }
              return null;
            })
        .given(this.jdbcTemplate)
        .query(anyString(), any(RowCallbackHandler.class));
  }

  private static ResultSet row(String application, long revision) {
    return (ResultSet)
        java.lang.reflect.Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              if ("getString".equals(method.getName())) {
                return application;
              }
              if ("getLong".equals(method.getName())) {
                return revision;
              }
              return null;
            });
  }

  @Test
  @DisplayName("first observation adopts a baseline and does NOT broadcast")
  void firstObservationDoesNotBroadcast() {
    this.revisions.put("inventory-service", 5L);

    this.detector.reconcileNow("test");

    // Broadcasting here would refresh every client on every Config Server restart.
    assertThat(this.published).isEmpty();
    assertThat(this.detector.lastReconcileAt()).isNotNull();
  }

  @Test
  @DisplayName("a revision that moved while nothing was listening triggers a broadcast")
  void broadcastsOnRevisionDrift() {
    this.revisions.put("inventory-service", 5L);
    this.detector.reconcileNow("baseline");

    this.revisions.put("inventory-service", 6L);
    this.detector.reconcileNow("test");

    assertThat(this.published).hasSize(1);
    assertThat(this.published.get(0).applications()).containsExactly("inventory-service");
    assertThat(this.published.get(0).source()).isEqualTo("jdbc-poll");
    assertThat(this.detector.reconcileBroadcasts()).isEqualTo(1L);
  }

  @Test
  @DisplayName("an unchanged revision is not re-broadcast on every poll")
  void doesNotRebroadcastUnchangedRevision() {
    this.revisions.put("inventory-service", 5L);
    this.detector.reconcileNow("baseline");
    this.detector.reconcileNow("poll-1");
    this.detector.reconcileNow("poll-2");

    assertThat(this.published).isEmpty();
  }

  @Test
  @DisplayName("a revision already handled via NOTIFY is not broadcast again by the poller")
  void notedRevisionIsNotDuplicated() {
    this.revisions.put("inventory-service", 5L);
    this.detector.reconcileNow("baseline");

    // The listener handled revision 6 already and recorded it.
    this.revisions.put("inventory-service", 6L);
    this.detector.noteObservedRevision("inventory-service", 6L);
    this.detector.reconcileNow("test");

    assertThat(this.published).isEmpty();
  }

  @Test
  @DisplayName("shared configuration drift broadcasts to all applications")
  void sharedConfigurationDriftBroadcastsToWildcard() {
    this.revisions.put("application", 1L);
    this.detector.reconcileNow("baseline");

    this.revisions.put("application", 2L);
    this.detector.reconcileNow("test");

    assertThat(this.published.get(0).applications()).containsExactly("*");
  }

  @Test
  @DisplayName("a database failure is contained: no broadcast, no exception escaping the scheduler")
  void containsDatabaseFailure() throws SQLException {
    willThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .given(this.jdbcTemplate)
        .query(anyString(), any(RowCallbackHandler.class));

    // An exception escaping here would kill the scheduled poller thread for the process lifetime.
    this.detector.reconcileNow("test");

    assertThat(this.published).isEmpty();
  }
}
