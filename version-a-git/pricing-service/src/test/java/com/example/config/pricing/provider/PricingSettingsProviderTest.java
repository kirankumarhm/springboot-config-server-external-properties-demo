package com.example.config.pricing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.config.pricing.config.PricingConfigProperties;
import com.example.config.pricing.config.SharedConfigProperties;
import com.example.config.pricing.domain.PricingSettings;
import com.example.config.pricing.refresh.ConfigRefreshAuditor;
import com.example.config.pricing.refresh.ConfigRefreshMetrics;
import com.example.config.pricing.refresh.ConfigSnapshotStatus;
import com.example.config.pricing.refresh.ConfigurationValidationException;
import com.example.config.pricing.refresh.EnvironmentChangeKeyRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the refresh algorithm this service depends on, plus snapshot assembly and validation
 * boundaries for the pricing service.
 *
 * <p>These are the tests that matter most in the project: they pin down last-known-good retention,
 * no-op detection, and read consistency, all of which are invisible in a happy-path demo but are
 * the difference between a live-refresh feature that is safe and one that silently serves
 * half-applied configuration.
 */
class PricingSettingsProviderTest {

  private PricingConfigProperties properties;
  private SharedConfigProperties shared;
  private ConfigRefreshAuditor auditor;
  private PricingSettingsProvider provider;

  @BeforeEach
  void setUp() {
    this.properties = validProperties();
    this.shared = validSharedProperties();
    this.auditor = new ConfigRefreshAuditor();
    this.provider = providerFor(this.properties, this.shared, this.auditor);
    this.provider.initialise();
  }

  private static PricingConfigProperties validProperties() {
    PricingConfigProperties properties = new PricingConfigProperties();
    properties.setCurrency("INR");
    properties.setDiscountPercentage(new BigDecimal("10.0"));
    properties.setSurgePricingEnabled(false);
    properties.setSurgeMultiplier(new BigDecimal("1.5"));
    return properties;
  }

  private static SharedConfigProperties validSharedProperties() {
    SharedConfigProperties shared = new SharedConfigProperties();
    shared.setBannerMessage("centrally configured");
    shared.setEnvironmentLabel("local");
    return shared;
  }

  private static PricingSettingsProvider providerFor(
      PricingConfigProperties properties,
      SharedConfigProperties shared,
      ConfigRefreshAuditor auditor) {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    return new PricingSettingsProvider(
        properties,
        shared,
        validator,
        new ConfigRefreshMetrics(new SimpleMeterRegistry()),
        auditor,
        new EnvironmentChangeKeyRecorder());
  }

  // ===================================================================================
  // Startup
  // ===================================================================================

  @Test
  @DisplayName("startup: valid configuration is adopted as version 1")
  void adoptsValidConfigurationAtStartup() {
    assertThat(this.provider.get().currency()).isEqualTo("INR");
    assertThat(this.provider.status().version()).isEqualTo(1L);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
  }

  @Test
  @DisplayName("startup: invalid configuration fails fast so the service never serves traffic")
  void failsFastOnInvalidConfigurationAtStartup() {
    PricingConfigProperties invalid = validProperties();
    invalid.setDiscountPercentage(new BigDecimal("99.0")); // violates @DecimalMax("90.0")
    PricingSettingsProvider freshProvider =
        providerFor(invalid, validSharedProperties(), new ConfigRefreshAuditor());

    assertThatThrownBy(freshProvider::initialise)
        .isInstanceOf(ConfigurationValidationException.class)
        .hasMessageContaining("pricing-service")
        .hasMessageContaining("discountPercentage");
  }

  // ===================================================================================
  // The refresh algorithm
  // ===================================================================================

  @Test
  @DisplayName("refresh: a changed discount is applied and the version increments")
  void changedDiscountIsApplied() {
    this.properties.setDiscountPercentage(new BigDecimal("25.0"));
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().discountPercentage()).isEqualByComparingTo("25.0");
    assertThat(this.provider.status().version()).isEqualTo(2L);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
  }

  @Test
  @DisplayName("refresh: no effective change is a no-op that does not bump the version")
  void noOpRefreshDoesNotBumpVersion() {
    long versionBefore = this.provider.status().version();

    this.provider.applyRefresh("test");

    assertThat(this.provider.status().version()).isEqualTo(versionBefore);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.NO_CHANGE);
  }

  @Test
  @DisplayName(
      "refresh: a discount above the allowed maximum is rejected, last-known-good retained")
  void rejectsOutOfRangeDiscount() {
    PricingSettings good = this.provider.get();
    long goodVersion = this.provider.status().version();

    // Simulate the rebinder having already written an invalid value into the bean.
    this.properties.setDiscountPercentage(new BigDecimal("99.0")); // violates @DecimalMax("90.0")
    this.provider.applyRefresh("test");

    assertThat(this.provider.get())
        .as("the previous valid snapshot must still be served")
        .isEqualTo(good);
    assertThat(this.provider.status().version()).isEqualTo(goodVersion);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().rejectedCount()).isEqualTo(1L);
    assertThat(this.provider.status().lastFailureReason()).contains("discountPercentage");
  }

  @Test
  @DisplayName("refresh: recovers after a rejected value is corrected")
  void recoversAfterCorrection() {
    this.properties.setDiscountPercentage(new BigDecimal("99.0"));
    this.provider.applyRefresh("test");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);

    this.properties.setDiscountPercentage(new BigDecimal("30.0"));
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().discountPercentage()).isEqualByComparingTo("30.0");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
    assertThat(this.provider.status().lastFailureReason()).isNull();
  }

  @Test
  @DisplayName("refresh: a currency code of the wrong length is rejected")
  void rejectsBadCurrencyCode() {
    PricingSettings good = this.provider.get();

    this.properties.setCurrency("RUPEES"); // violates @Size(min=3,max=3)
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }

  @Test
  @DisplayName("refresh: a surge multiplier below 1.0 is rejected: it would silently discount")
  void rejectsMultiplierBelowOne() {
    PricingSettings good = this.provider.get();

    this.properties.setSurgeMultiplier(new BigDecimal("0.5"));
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }

  @Test
  @DisplayName("audit: records key names and outcomes, never property values")
  void auditRecordsNamesNotValues() {
    this.shared.setBannerMessage("super-secret-banner");
    this.provider.applyRefresh("test");

    List<Map<String, Object>> history = this.auditor.history();
    assertThat(history).isNotEmpty();
    assertThat(history.toString())
        .as("property values must never appear in the audit trail")
        .doesNotContain("super-secret-banner");
    assertThat(history.get(0)).containsKeys("outcome", "changedKeys", "version", "timestamp");
  }

  @Test
  @DisplayName("concurrency: a reader never observes a partially applied snapshot")
  void readerNeverSeesTornSnapshot() throws Exception {
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
                PricingSettings s = this.provider.get();
                // Every snapshot published below pairs banner "v<N>" with discount N. Any other
                // combination could only come from reading a half-updated object - and because
                // the two values come from DIFFERENT properties beans, this also proves shared
                // and application-specific configuration are swapped in as one unit.
                if (s != null && s.bannerMessage().startsWith("v")) {
                  int expected = Integer.parseInt(s.bannerMessage().substring(1));
                  if (s.discountPercentage().intValueExact() != expected) {
                    torn.set(true);
                  }
                }
              }
            });
      }
      started.await(5, TimeUnit.SECONDS);

      for (int n = 1; n <= 60; n++) {
        this.shared.setBannerMessage("v" + n);
        this.properties.setDiscountPercentage(new BigDecimal(n));
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
    assertThat(this.provider.get().bannerMessage()).isEqualTo("v60");
    assertThat(this.provider.get().discountPercentage()).isEqualByComparingTo("60");
  }

  // ===================================================================================
  // Snapshot assembly
  // ===================================================================================

  @Test
  @DisplayName("snapshot merges application-specific and shared configuration")
  void snapshotMergesBothSources() {
    assertThat(this.provider.get().currency()).isEqualTo("INR");
    assertThat(this.provider.get().discountPercentage()).isEqualByComparingTo("10.0");
    assertThat(this.provider.get().surgeMultiplier()).isEqualByComparingTo("1.5");
    assertThat(this.provider.get().environmentLabel()).isEqualTo("local");
    assertThat(this.provider.status().application()).isEqualTo("pricing-service");
  }

  @Test
  @DisplayName("a change to SHARED configuration refreshes this application too")
  void sharedConfigurationChangeIsPickedUp() {
    long before = this.provider.status().version();

    this.shared.setEnvironmentLabel("production-like");
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().environmentLabel()).isEqualTo("production-like");
    assertThat(this.provider.status().version()).isGreaterThan(before);
  }

  @Test
  @DisplayName("invalid SHARED configuration also rejects the refresh")
  void invalidSharedConfigurationIsRejected() {
    PricingSettings good = this.provider.get();

    this.shared.setBannerMessage("  "); // violates @NotBlank
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().lastFailureReason()).contains("bannerMessage");
  }
}
