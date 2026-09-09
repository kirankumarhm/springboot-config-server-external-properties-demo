package com.example.config.inventory.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.config.commons.ConfigRefreshAuditor;
import com.example.config.commons.ConfigRefreshMetrics;
import com.example.config.commons.ConfigSnapshotStatus;
import com.example.config.commons.EnvironmentChangeKeyRecorder;
import com.example.config.commons.SharedConfigProperties;
import com.example.config.inventory.config.InventoryConfigProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the inventory snapshot is assembled from both application-specific and shared config.
 */
class InventorySettingsProviderTest {

  private InventoryConfigProperties properties;
  private SharedConfigProperties shared;
  private InventorySettingsProvider provider;

  @BeforeEach
  void setUp() {
    this.properties = new InventoryConfigProperties();
    this.properties.setWarehouseCode("WH-BLR-01");
    this.properties.setMaxOrderQuantity(500);
    this.properties.setExpressShippingEnabled(false);
    this.properties.setLowStockThreshold(25);
    this.properties.setDownstreamApiKey("downstream-api-key-value");

    this.shared = new SharedConfigProperties();
    this.shared.setBannerMessage("centrally configured");
    this.shared.setEnvironmentLabel("local");

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    this.provider =
        new InventorySettingsProvider(
            this.properties,
            this.shared,
            validator,
            new ConfigRefreshMetrics(new SimpleMeterRegistry()),
            new ConfigRefreshAuditor(),
            new EnvironmentChangeKeyRecorder());
    this.provider.applyRefresh("test-setup");
  }

  @Test
  @DisplayName("the snapshot carries a FINGERPRINT of the secret, never the secret itself")
  void snapshotNeverCarriesTheSecret() {
    String fingerprint = this.provider.get().downstreamApiKeyFingerprint();

    assertThat(fingerprint).startsWith("sha256:");
    assertThat(fingerprint).doesNotContain("downstream-api-key-value");
    assertThat(this.provider.get().toString())
        .as("the record is serialised onto an inspection endpoint; it must not leak the secret")
        .doesNotContain("downstream-api-key-value");
  }

  @Test
  @DisplayName("a missing secret is rejected, not silently served as blank")
  void missingSecretIsRejected() {
    var good = this.provider.get();

    this.properties.setDownstreamApiKey("  ");
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome())
        .isEqualTo(com.example.config.commons.ConfigSnapshotStatus.REJECTED);
  }

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
    var good = this.provider.get();

    this.shared.setBannerMessage("  "); // violates @NotBlank
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().lastFailureReason()).contains("bannerMessage");
  }
}
