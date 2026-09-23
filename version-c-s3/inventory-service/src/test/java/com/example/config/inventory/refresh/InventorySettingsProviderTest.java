package com.example.config.inventory.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.config.inventory.config.InventoryConfigProperties;
import com.example.config.inventory.config.SharedConfigProperties;
import com.example.config.inventory.domain.InventorySettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
 * Tests the refresh algorithm this service depends on, plus snapshot assembly from both
 * application-specific and shared configuration.
 *
 * <p>These are the tests that matter most in the project: they pin down last-known-good retention,
 * no-op detection, and read consistency, all of which are invisible in a happy-path demo but are
 * the difference between a live-refresh feature that is safe and one that silently serves
 * half-applied configuration.
 */
class InventorySettingsProviderTest {

  private InventoryConfigProperties properties;
  private SharedConfigProperties shared;
  private ConfigRefreshAuditor auditor;
  private InventorySettingsProvider provider;

  @BeforeEach
  void setUp() {
    this.properties = validProperties();
    this.shared = validSharedProperties();
    this.auditor = new ConfigRefreshAuditor();
    this.provider = providerFor(this.properties, this.shared, this.auditor);
    this.provider.initialise();
  }

  private static InventoryConfigProperties validProperties() {
    InventoryConfigProperties properties = new InventoryConfigProperties();
    properties.setWarehouseCode("WH-BLR-01");
    properties.setMaxOrderQuantity(500);
    properties.setExpressShippingEnabled(false);
    properties.setLowStockThreshold(25);
    return properties;
  }

  private static SharedConfigProperties validSharedProperties() {
    SharedConfigProperties shared = new SharedConfigProperties();
    shared.setBannerMessage("centrally configured");
    shared.setEnvironmentLabel("local");
    return shared;
  }

  private static InventorySettingsProvider providerFor(
      InventoryConfigProperties properties,
      SharedConfigProperties shared,
      ConfigRefreshAuditor auditor) {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    return new InventorySettingsProvider(
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
    assertThat(this.provider.get().warehouseCode()).isEqualTo("WH-BLR-01");
    assertThat(this.provider.status().version()).isEqualTo(1L);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
  }

  @Test
  @DisplayName("startup: invalid configuration fails fast so the service never serves traffic")
  void failsFastOnInvalidConfigurationAtStartup() {
    InventoryConfigProperties invalid = validProperties();
    invalid.setMaxOrderQuantity(99_999); // violates @Max(10_000)
    InventorySettingsProvider freshProvider =
        providerFor(invalid, validSharedProperties(), new ConfigRefreshAuditor());

    assertThatThrownBy(freshProvider::initialise)
        .isInstanceOf(ConfigurationValidationException.class)
        .hasMessageContaining("inventory-service")
        .hasMessageContaining("maxOrderQuantity");
  }

  // ===================================================================================
  // The refresh algorithm
  // ===================================================================================

  @Test
  @DisplayName("refresh: a changed value is applied and the version increments")
  void appliesChangedValue() {
    this.properties.setMaxOrderQuantity(1_000);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().maxOrderQuantity()).isEqualTo(1_000);
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
  @DisplayName("refresh: invalid configuration is REJECTED and last-known-good is retained")
  void retainsLastKnownGoodWhenRefreshIsInvalid() {
    InventorySettings good = this.provider.get();
    long goodVersion = this.provider.status().version();

    // Simulate the rebinder having already written an invalid value into the bean.
    this.properties.setMaxOrderQuantity(99_999);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get())
        .as("the previous valid snapshot must still be served")
        .isEqualTo(good);
    assertThat(this.provider.status().version()).isEqualTo(goodVersion);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().rejectedCount()).isEqualTo(1L);
    assertThat(this.provider.status().lastFailureReason()).contains("maxOrderQuantity");
  }

  @Test
  @DisplayName("refresh: recovers after a rejected value is corrected")
  void recoversAfterCorrection() {
    this.properties.setMaxOrderQuantity(99_999);
    this.provider.applyRefresh("test");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);

    this.properties.setMaxOrderQuantity(750);
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().maxOrderQuantity()).isEqualTo(750);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.APPLIED);
    assertThat(this.provider.status().lastFailureReason()).isNull();
  }

  @Test
  @DisplayName("refresh: a blank required value is rejected, not adopted")
  void rejectsBlankRequiredValue() {
    this.properties.setWarehouseCode("   ");
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().warehouseCode()).isEqualTo("WH-BLR-01");
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }

  @Test
  @DisplayName("audit: records key names and outcomes, never property values")
  void auditRecordsNamesNotValues() {
    this.properties.setWarehouseCode("WH-SUPER-SECRET");
    this.provider.applyRefresh("test");

    List<Map<String, Object>> history = this.auditor.history();
    assertThat(history).isNotEmpty();
    assertThat(history.toString())
        .as("property values must never appear in the audit trail")
        .doesNotContain("WH-SUPER-SECRET");
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
                InventorySettings s = this.provider.get();
                // Every snapshot published below pairs warehouse code "WH-<N>" with max order
                // quantity N. Any other combination could only come from reading a half-updated
                // object.
                if (s != null && s.warehouseCode().startsWith("WH-0")) {
                  int expected = Integer.parseInt(s.warehouseCode().substring(4));
                  if (s.maxOrderQuantity() != expected) {
                    torn.set(true);
                  }
                }
              }
            });
      }
      started.await(5, TimeUnit.SECONDS);

      for (int n = 1; n <= 60; n++) {
        this.properties.setWarehouseCode("WH-0" + n);
        this.properties.setMaxOrderQuantity(n);
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
    assertThat(this.provider.get().warehouseCode()).isEqualTo("WH-060");
    assertThat(this.provider.get().maxOrderQuantity()).isEqualTo(60);
  }

  // ===================================================================================
  // Snapshot assembly
  // ===================================================================================

  @Test
  @DisplayName("snapshot merges application-specific and shared configuration")
  void snapshotMergesBothSources() {
    assertThat(this.provider.get().warehouseCode()).isEqualTo("WH-BLR-01");
    assertThat(this.provider.get().maxOrderQuantity()).isEqualTo(500);
    assertThat(this.provider.get().bannerMessage()).isEqualTo("centrally configured");
    assertThat(this.provider.get().environmentLabel()).isEqualTo("local");
    assertThat(this.provider.status().application()).isEqualTo("inventory-service");
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
    InventorySettings good = this.provider.get();

    this.shared.setBannerMessage("  "); // violates @NotBlank
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().lastFailureReason()).contains("bannerMessage");
  }
}
