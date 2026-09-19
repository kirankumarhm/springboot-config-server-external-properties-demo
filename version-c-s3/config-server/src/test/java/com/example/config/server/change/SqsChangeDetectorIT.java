package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the S3 change-detection path, against a real SQS queue.
 *
 * <p>Scope is deliberately the SQS consumer and not the S3 repository. {@code
 * AwsS3EnvironmentRepositoryFactory.build()} constructs its own {@code S3Client} internally with no
 * injection point and no path-style option, so it can only reach a bucket whose virtual-host name
 * resolves in DNS. A Testcontainers instance on a random port cannot satisfy that, which is exactly
 * why the deployed stack needs {@code extra_hosts} entries. The consumer, the key-to-application
 * mapping and the broadcast are the parts this project owns, and they are what is verified here.
 *
 * <p>Uses the Floci emulator image, which is already present locally and is the emulator this
 * project standardises on.
 */
@Testcontainers
@SpringBootTest(
    classes = SqsChangeDetectorIT.TestApp.class,
    properties = {
      "spring.main.web-application-type=none",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
      "app.known-applications=inventory-service,pricing-service",
      "app.config-change.s3.queue-name=it-config-change-queue",
      "app.config-change.s3.key-prefix=main/",
      "spring.cloud.aws.region.static=us-east-1",
      "spring.cloud.aws.credentials.access-key=test",
      "spring.cloud.aws.credentials.secret-key=test"
    })
class SqsChangeDetectorIT {

  private static final String QUEUE = "it-config-change-queue";

  @Container
  static final GenericContainer<?> FLOCI =
      new GenericContainer<>(DockerImageName.parse("floci/floci:latest"))
          .withExposedPorts(4566)
          .waitingFor(
              Wait.forLogMessage(".*Ready.*\\n", 1).withStartupTimeout(Duration.ofMinutes(1)));

  @Autowired private SqsTemplate sqsTemplate;
  @Autowired private RecordingPublisher publisher;

  @DynamicPropertySource
  static void awsEndpoint(DynamicPropertyRegistry registry) {
    String endpoint = "http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566);
    registry.add("spring.cloud.aws.endpoint", () -> endpoint);
    registry.add("spring.cloud.aws.sqs.endpoint", () -> endpoint);
  }

  @BeforeEach
  void reset() {
    this.publisher.published.clear();
  }

  private void sendS3Event(String key) {
    this.sqsTemplate.send(
        QUEUE,
        """
        {"Records":[{"eventVersion":"2.1","eventSource":"aws:s3","awsRegion":"us-east-1",
        "eventName":"ObjectCreated:Put",
        "s3":{"bucket":{"name":"acme-platform-config"},
        "object":{"key":"%s","size":120,"versionId":"v1"}}}]}
        """
            .formatted(key));
  }

  @Test
  @DisplayName("an S3 event for one application broadcasts to exactly that application")
  void scopedBroadcast() {
    sendS3Event("main/inventory-service.yml");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(this.publisher.published)
                    .singleElement()
                    .satisfies(
                        n -> assertThat(n.applications()).containsExactly("inventory-service")));
    assertThat(this.publisher.published.get(0).source()).isEqualTo("s3-sqs");
  }

  @Test
  @DisplayName("a shared object broadcasts to every application")
  void sharedObjectBroadcastsToAll() {
    sendS3Event("main/application.yml");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(this.publisher.published)
                    .singleElement()
                    .satisfies(n -> assertThat(n.applications()).containsExactly("*")));
  }

  @Test
  @DisplayName("a profile-suffixed key maps to exactly one application, without dash-guessing")
  void profileSuffixedKeyMapsExactly() {
    sendS3Event("main/inventory-service-dev.yml");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(this.publisher.published)
                    .singleElement()
                    .satisfies(
                        n -> assertThat(n.applications()).containsExactly("inventory-service")));
  }

  @Test
  @DisplayName("an object outside the configured prefix is ignored")
  void keyOutsidePrefixIsIgnored() throws InterruptedException {
    sendS3Event("other/inventory-service.yml");

    // Nothing should ever be published, so assert it stays empty rather than waiting for a change.
    Thread.sleep(Duration.ofSeconds(6).toMillis());
    assertThat(this.publisher.published).isEmpty();
  }

  @Test
  @DisplayName("an unparseable message does not stop later messages being processed")
  void poisonMessageDoesNotBlockTheQueue() {
    this.sqsTemplate.send(QUEUE, "this-is-not-json");
    sendS3Event("main/pricing-service.yml");

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(this.publisher.published)
                    .anySatisfy(
                        n -> assertThat(n.applications()).containsExactly("pricing-service")));
  }

  /**
   * Minimal PRIMARY configuration: the detector, the mapper, and a publisher that records instead
   * of broadcasting.
   *
   * <p>Annotated {@code @SpringBootConfiguration} rather than {@code @TestConfiguration} on
   * purpose. {@code @TestConfiguration} is additive, so Spring would still search for the real
   * {@code ConfigServerApplication} and boot the whole server - S3 repository, Bus and keystore
   * included - none of which this test needs.
   */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({S3EventSqsChangeDetector.class, S3ObjectKeyApplicationMapper.class})
  static class TestApp {

    @Bean
    RecordingPublisher recordingPublisher() {
      return new RecordingPublisher();
    }
  }

  /**
   * Captures broadcasts so the assertions do not need a running RabbitMQ. What matters here is that
   * the SQS consumer decoded the event and resolved the right destinations.
   */
  static class RecordingPublisher implements ConfigChangePublisher {

    private final List<ConfigChangeNotification> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ConfigChangeNotification notification) {
      this.published.add(notification);
    }

    List<ConfigChangeNotification> snapshot() {
      return new ArrayList<>(this.published);
    }
  }
}
