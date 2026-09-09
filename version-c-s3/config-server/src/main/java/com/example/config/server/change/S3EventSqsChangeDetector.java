package com.example.config.server.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects configuration changes from S3 Event Notifications delivered via SQS.
 *
 * <p>Unlike a database, S3 has a native change-notification mechanism, so this is a genuine push
 * path rather than something that had to be constructed. The trade-off is delivery semantics: S3
 * events are <b>at-least-once and unordered</b>, so the same change can arrive several times. That
 * is safe here because a refresh is idempotent — the provider compares the rebuilt snapshot and
 * reports NO_CHANGE without bumping the version — and duplicates within one batch are collapsed
 * before publishing.
 */
@Component
public class S3EventSqsChangeDetector {

  private static final Logger log = LoggerFactory.getLogger(S3EventSqsChangeDetector.class);

  private final ConfigChangePublisher publisher;
  private final S3ObjectKeyApplicationMapper mapper;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final AtomicReference<Instant> lastEvent = new AtomicReference<>();
  private final AtomicLong eventsReceived = new AtomicLong();
  private final AtomicLong recordsIgnored = new AtomicLong();

  public S3EventSqsChangeDetector(
      ConfigChangePublisher publisher, S3ObjectKeyApplicationMapper mapper) {
    this.publisher = publisher;
    this.mapper = mapper;
  }

  /**
   * The message body is parsed as raw JSON rather than a typed S3 event, so an unexpected or
   * partially-supported emulator payload cannot fail deserialisation before the keys are read.
   */
  @SqsListener("${app.config-change.s3.queue-name}")
  public void onBucketEvent(String body) {
    try {
      JsonNode root = this.objectMapper.readTree(body);
      JsonNode records = root.path("Records");
      if (!records.isArray() || records.isEmpty()) {
        return;
      }

      Set<String> destinations = new LinkedHashSet<>();
      for (JsonNode record : records) {
        String rawKey = record.path("s3").path("object").path("key").asText("");
        if (rawKey.isEmpty()) {
          continue;
        }
        // S3 delivers keys URL-encoded ('+' for space, %3D for '='). Skipping this corrupts the
        // key and the mapping silently misses.
        String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);

        if (!this.mapper.isConfigObject(key)) {
          this.recordsIgnored.incrementAndGet();
          continue;
        }
        this.mapper.toApplication(key).ifPresent(destinations::add);
      }

      if (destinations.isEmpty()) {
        return;
      }

      this.eventsReceived.incrementAndGet();
      this.lastEvent.set(Instant.now());

      this.publisher.publish(
          new ConfigChangeNotification(
              destinations,
              "s3-sqs",
              root.path("Records")
                  .path(0)
                  .path("responseElements")
                  .path("x-amz-request-id")
                  .asText("unknown"),
              Instant.now()));
    } catch (Exception ex) {
      // Rethrow: the listener acknowledges on success only, so the message returns to the queue
      // and after maxReceiveCount lands in the dead-letter queue instead of being lost.
      log.error("Failed to handle S3 event notification: {}", ex.getMessage());
      throw new IllegalStateException("Unprocessable S3 event notification", ex);
    }
  }

  public Instant lastEventAt() {
    return this.lastEvent.get();
  }

  public long eventsReceived() {
    return this.eventsReceived.get();
  }

  public long recordsIgnored() {
    return this.recordsIgnored.get();
  }
}
