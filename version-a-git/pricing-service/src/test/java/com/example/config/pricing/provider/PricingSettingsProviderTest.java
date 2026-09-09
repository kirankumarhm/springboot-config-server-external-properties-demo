package com.example.config.pricing.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.config.commons.ConfigRefreshAuditor;
import com.example.config.commons.ConfigRefreshMetrics;
import com.example.config.commons.ConfigSnapshotStatus;
import com.example.config.commons.EnvironmentChangeKeyRecorder;
import com.example.config.commons.SharedConfigProperties;
import com.example.config.pricing.config.PricingConfigProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies snapshot assembly and validation boundaries for the pricing service. */
class PricingSettingsProviderTest {

  private PricingConfigProperties properties;
  private SharedConfigProperties shared;
  private PricingSettingsProvider provider;

  @BeforeEach
  void setUp() {
    this.properties = new PricingConfigProperties();
    this.properties.setCurrency("INR");
    this.properties.setDiscountPercentage(new BigDecimal("10.0"));
    this.properties.setSurgePricingEnabled(false);
    this.properties.setSurgeMultiplier(new BigDecimal("1.5"));

    this.shared = new SharedConfigProperties();
    this.shared.setBannerMessage("centrally configured");
    this.shared.setEnvironmentLabel("local");

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    this.provider =
        new PricingSettingsProvider(
            this.properties,
            this.shared,
            validator,
            new ConfigRefreshMetrics(new SimpleMeterRegistry()),
            new ConfigRefreshAuditor(),
            new EnvironmentChangeKeyRecorder());
    this.provider.applyRefresh("test-setup");
  }

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
  @DisplayName("a changed discount is applied and bumps the version")
  void changedDiscountIsApplied() {
    long before = this.provider.status().version();

    this.properties.setDiscountPercentage(new BigDecimal("25.0"));
    this.provider.applyRefresh("test");

    assertThat(this.provider.get().discountPercentage()).isEqualByComparingTo("25.0");
    assertThat(this.provider.status().version()).isGreaterThan(before);
  }

  @Test
  @DisplayName("a discount above the allowed maximum is rejected, last-known-good retained")
  void rejectsOutOfRangeDiscount() {
    var good = this.provider.get();

    this.properties.setDiscountPercentage(new BigDecimal("99.0")); // violates @DecimalMax("90.0")
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
    assertThat(this.provider.status().lastFailureReason()).contains("discountPercentage");
  }

  @Test
  @DisplayName("a currency code of the wrong length is rejected")
  void rejectsBadCurrencyCode() {
    var good = this.provider.get();

    this.properties.setCurrency("RUPEES"); // violates @Size(min=3,max=3)
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }

  @Test
  @DisplayName("a surge multiplier below 1.0 is rejected: it would silently discount")
  void rejectsMultiplierBelowOne() {
    var good = this.provider.get();

    this.properties.setSurgeMultiplier(new BigDecimal("0.5"));
    this.provider.applyRefresh("test");

    assertThat(this.provider.get()).isEqualTo(good);
    assertThat(this.provider.status().lastOutcome()).isEqualTo(ConfigSnapshotStatus.REJECTED);
  }
}
