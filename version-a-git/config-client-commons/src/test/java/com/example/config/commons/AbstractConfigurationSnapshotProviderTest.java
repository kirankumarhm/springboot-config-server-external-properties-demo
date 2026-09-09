package com.example.config.commons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the refresh algorithm every client depends on.
 *
 * <p>These are the tests that matter most in the project: they pin down last-known-good retention,
 * no-op detection, and read consistency, all of which are invisible in a happy-path demo but are
 * the difference between a live-refresh feature that is safe and one that silently serves
 * half-applied configuration.
 */
class AbstractConfigurationSnapshotProviderTest {

  /** Stand-in for a real setter-bound {@code @ConfigurationProperties} bean. */
  static class TestProperties {
    @NotBlank private String name = "initial";

    @Min(1)
    @Max(100)
    private int limit = 10;

    String getName() {
      return this.name;
    }

    void setName(String name) {
      this.name = name;
    }

    int getLimit() {
      return this.limit;
    }

    void setLimit(int limit) {
      this.limit = limit;
    }
  }

  record TestSettings(String name, int limit) {}

  static class TestProvider extends AbstractConfigurationSnapshotProvider<TestSettings> {
    private final TestProperties properties;
    final AtomicInteger buildCount = new AtomicInteger();

    TestProvider(
        TestProperties properties,
        Validator validator,
        ConfigRefreshMetrics metrics,
        ConfigRefreshAuditor auditor,
        EnvironmentChangeKeyRecorder recorder) {
      super(validator, metrics, auditor, recorder);
      this.properties = properties;
    }

    @Override
    protected String applicationName() {
      return "test-service";
    }

    @Override
    protected List<Object> propertiesBeans() {
      return List.of(this.properties);
    }

    @Override
    protected TestSettings buildSnapshot() {
      this.buildCount.incrementAndGet();
      return new TestSettings(this.properties.getName(), this.properties.getLimit());
    }
  }

  private TestProperties properties;
  private ConfigRefreshAuditor auditor;
  private TestProvider provider;

  @BeforeEach
  void setUp() {
    this.properties = new TestProperties();
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    ConfigRefreshMetrics metrics = new ConfigRefreshMetrics(new SimpleMeterRegistry());
    this.auditor = new ConfigRefreshAuditor();
    this.provider =
        new TestProvider(
            this.properties, validator, metrics, this.auditor, new EnvironmentChangeKeyRecorder());
  }

  @Test
  @DisplayName("startup: valid configuration is adopted as version 1")
  void adoptsValidConfigurationAtStartup() {
    this.provider.initialise();

    assertThat(this.provider.get()).isEqualTo(new TestSettings("initial", 10));
    assertThat(this.provider.status().version()).isEqualTo(1L);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
  }

  @Test
  @DisplayName("startup: invalid configuration fails fast so the service never serves traffic")
  void failsFastOnInvalidConfigurationAtStartup() {
    this.properties.setLimit(9999); // violates @Max(100)

    assertThatThrownBy(() -> this.provider.initialise())
        .isInstanceOf(ConfigurationValidationException.class)
        .hasMessageContaining("test-service")
        .hasMessageContaining("limit");
  }

  @Test
  @DisplayName("refresh: a changed value is applied and the version increments")
  void appliesChangedValue() {
    this.provider.initialise();

    this.properties.setLimit(42);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().limit()).isEqualTo(42);
    assertThat(this.provider.status().version()).isEqualTo(2L);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
  }

  @Test
  @DisplayName("refresh: no effective change is a no-op that does not bump the version")
  void noOpRefreshDoesNotBumpVersion() {
    this.provider.initialise();
    long versionBefore = this.provider.status().version();

    this.provider.applyRefresh("test");

    assertThat(this.provider.status().version()).isEqualTo(versionBefore);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.NO_CHANGE);
  }

  @Test
  @DisplayName("refresh: invalid configuration is REJECTED and last-known-good is retained")
  void retainsLastKnownGoodWhenRefreshIsInvalid() {
    this.provider.initialise();
    TestSettings good = this.provider.get();
    long goodVersion = this.provider.status().version();

    // Simulate the rebinder having already written an invalid value into the bean.
    this.properties.setLimit(9999);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get())
        .as("the previous valid snapshot must still be served")
        .isEqualTo(good);
    assertThat(this.provider.status().version()).isEqualTo(goodVersion);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().rejectedCount()).isEqualTo(1L);
    assertThat(this.provider.status().lastFailureReason()).contains("limit");
  }

  @Test
  @DisplayName("refresh: recovers after a rejected value is corrected")
  void recoversAfterCorrection() {
    this.provider.initialise();
    this.properties.setLimit(9999);
    this.provider.applyRefresh("test");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);

    this.properties.setLimit(55);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().limit()).isEqualTo(55);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
    assertThat(this.provider.status().lastFailureReason()).isNull();
  }

  @Test
  @DisplayName("refresh: a blank required value is rejected, not adopted")
  void rejectsBlankRequiredValue() {
    this.provider.initialise();

    this.properties.setName("   ");
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().name()).isEqualTo("initial");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }

  @Test
  @DisplayName("audit: records key names and outcomes, never property values")
  void auditRecordsNamesNotValues() {
    this.provider.initialise();
    this.properties.setName("super-secret-value");
    this.provider.applyRefresh("test");

    List<Map<String, Object>> history = this.auditor.history();
    assertThat(history).isNotEmpty();
    assertThat(history.toString())
        .as("property values must never appear in the audit trail")
        .doesNotContain("super-secret-value");
    assertThat(history.get(0)).containsKeys("outcome", "changedKeys", "version", "timestamp");
  }

  @Test
  @DisplayName("concurrency: a reader never observes a partially applied snapshot")
  void readerNeverSeesTornSnapshot() throws Exception {
    this.provider.initialise();

    int readers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
    AtomicBoolean torn = new AtomicBoolean(false);
    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch started = new CountDownLatch(readers);

    try {
      for (int i = 0; i < readers; i++) {
        pool.submit(
            () -> {
              started.countDown();
              while (running.get()) {
                TestSettings s = this.provider.get();
                // Every snapshot ever published pairs name "v<N>" with limit N. Any other
                // combination could only come from reading a half-updated object.
                if (s != null && s.name().startsWith("v")) {
                  int expected = Integer.parseInt(s.name().substring(1));
                  if (s.limit() != expected) {
                    torn.set(true);
                  }
                }
              }
            });
      }
      started.await(5, TimeUnit.SECONDS);

      for (int n = 1; n <= 60; n++) {
        this.properties.setName("v" + n);
        this.properties.setLimit(n);
        this.provider.applyRefresh("test");
      }
      running.set(false);
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(torn)
        .as("a snapshot mixing values from two configuration generations was observed")
        .isFalse();
    assertThat(this.provider.get()).isEqualTo(new TestSettings("v60", 60));
  }
}
