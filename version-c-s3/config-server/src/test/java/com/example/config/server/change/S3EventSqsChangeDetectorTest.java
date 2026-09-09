package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies handling of S3 event notifications, whose delivery is at-least-once and unordered.
 *
 * <p>Payloads here are the real shape observed from the emulator, including the URL-encoded object
 * keys that S3 actually sends.
 */
class S3EventSqsChangeDetectorTest {

  private final List<ConfigChangeNotification> published = new ArrayList<>();
  private S3EventSqsChangeDetector detector;

  @BeforeEach
  void setUp() {
    this.published.clear();
    ConfigChangePublisher publisher = this.published::add;
    this.detector =
        new S3EventSqsChangeDetector(
            publisher,
            new S3ObjectKeyApplicationMapper(
                "main/", List.of("inventory-service", "pricing-service")));
  }

  private static String event(String... keys) {
    StringBuilder records = new StringBuilder();
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) {
        records.append(',');
      }
      records
          .append("{\"eventName\":\"ObjectCreated:Put\",\"s3\":{\"bucket\":{\"name\":\"b\"},")
          .append("\"object\":{\"key\":\"")
          .append(keys[i])
          .append("\",\"size\":12}}}");
    }
    return "{\"Records\":[" + records + "]}";
  }

  @Test
  @DisplayName("an object event publishes exactly one scoped notification")
  void publishesScopedNotification() {
    this.detector.onBucketEvent(event("main/inventory-service.yml"));

    assertThat(this.published).hasSize(1);
    assertThat(this.published.get(0).applications()).containsExactly("inventory-service");
    assertThat(this.published.get(0).source()).isEqualTo("s3-sqs");
    assertThat(this.detector.eventsReceived()).isEqualTo(1L);
    assertThat(this.detector.lastEventAt()).isNotNull();
  }

  @Test
  @DisplayName("URL-encoded keys are decoded before mapping")
  void decodesUrlEncodedKeys() {
    // S3 delivers keys URL-encoded. Without decoding, this key would not match anything.
    this.detector.onBucketEvent(event("main%2Finventory-service.yml"));

    assertThat(this.published).hasSize(1);
    assertThat(this.published.get(0).applications()).containsExactly("inventory-service");
  }

  @Test
  @DisplayName("duplicate keys within one batch collapse to a single destination")
  void deduplicatesWithinBatch() {
    this.detector.onBucketEvent(
        event(
            "main/inventory-service.yml",
            "main/inventory-service.yml",
            "main/inventory-service.yml"));

    assertThat(this.published).hasSize(1);
    assertThat(this.published.get(0).applications()).containsExactly("inventory-service");
  }

  @Test
  @DisplayName("a batch touching several applications publishes one notification listing all")
  void handlesMultipleApplicationsInOneBatch() {
    this.detector.onBucketEvent(event("main/inventory-service.yml", "main/pricing-service.yml"));

    assertThat(this.published).hasSize(1);
    assertThat(this.published.get(0).applications())
        .containsExactlyInAnyOrder("inventory-service", "pricing-service");
  }

  @Test
  @DisplayName("shared configuration maps to the all-applications destination")
  void sharedConfigurationBroadcastsToAll() {
    this.detector.onBucketEvent(event("main/application.yml"));

    assertThat(this.published.get(0).applications()).containsExactly("*");
  }

  @Test
  @DisplayName("non-configuration objects are ignored, not broadcast")
  void ignoresNonConfigObjects() {
    this.detector.onBucketEvent(event("main/readme.txt", "other/inventory-service.yml"));

    assertThat(this.published).isEmpty();
    assertThat(this.detector.recordsIgnored()).isEqualTo(2L);
  }

  @Test
  @DisplayName("an empty or record-less payload is a silent no-op")
  void handlesEmptyPayload() {
    this.detector.onBucketEvent("{\"Records\":[]}");
    this.detector.onBucketEvent("{}");

    assertThat(this.published).isEmpty();
  }

  @Test
  @DisplayName("an unparseable message throws so SQS can redrive it to the dead-letter queue")
  void throwsOnUnparseableMessage() {
    // Swallowing this would silently delete a poison message instead of surfacing it in the DLQ.
    assertThatThrownBy(() -> this.detector.onBucketEvent("this-is-not-json"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unprocessable");

    assertThat(this.published).isEmpty();
  }
}
