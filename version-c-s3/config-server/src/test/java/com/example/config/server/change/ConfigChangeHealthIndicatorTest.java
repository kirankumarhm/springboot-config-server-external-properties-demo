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

/** Verifies the S3/SQS detector state is exposed, including its delivery semantics. */
@ExtendWith(MockitoExtension.class)
class ConfigChangeHealthIndicatorTest {

  @Mock private S3EventSqsChangeDetector detector;

  @Test
  @DisplayName("reports the mechanism, counters and last event")
  void reportsDetectorState() {
    given(this.detector.eventsReceived()).willReturn(5L);
    given(this.detector.recordsIgnored()).willReturn(2L);
    given(this.detector.lastEventAt()).willReturn(Instant.parse("2026-08-30T10:00:00Z"));

    Health health = new ConfigChangeHealthIndicator(this.detector).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("mechanism", "S3 Event Notifications -> SQS")
        .containsEntry("eventsReceived", 5L)
        .containsEntry("recordsIgnored", 2L)
        .containsEntry("lastEventAt", "2026-08-30T10:00:00Z");
  }

  @Test
  @DisplayName("before any event, lastEventAt reads 'never' rather than null")
  void reportsNeverBeforeFirstEvent() {
    given(this.detector.eventsReceived()).willReturn(0L);
    given(this.detector.recordsIgnored()).willReturn(0L);
    given(this.detector.lastEventAt()).willReturn(null);

    Health health = new ConfigChangeHealthIndicator(this.detector).health();

    assertThat(health.getDetails()).containsEntry("lastEventAt", "never");
    // The at-least-once contract is documented in health so an operator reading it understands
    // why duplicate refreshes are expected and harmless.
    assertThat(health.getDetails().get("deliverySemantics").toString()).contains("at-least-once");
  }
}
