package com.example.config.pricing.provider;

import com.example.config.commons.AbstractConfigurationSnapshotProvider;
import com.example.config.commons.ConfigRefreshAuditor;
import com.example.config.commons.ConfigRefreshMetrics;
import com.example.config.commons.EnvironmentChangeKeyRecorder;
import com.example.config.commons.SharedConfigProperties;
import com.example.config.pricing.config.PricingConfigProperties;
import com.example.config.pricing.domain.PricingSettings;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Publishes validated, immutable {@link PricingSettings} snapshots to business code. */
@Component
public class PricingSettingsProvider
    extends AbstractConfigurationSnapshotProvider<PricingSettings> {

  private final PricingConfigProperties properties;
  private final SharedConfigProperties shared;

  public PricingSettingsProvider(
      PricingConfigProperties properties,
      SharedConfigProperties shared,
      Validator validator,
      ConfigRefreshMetrics metrics,
      ConfigRefreshAuditor auditor,
      EnvironmentChangeKeyRecorder keyRecorder) {
    super(validator, metrics, auditor, keyRecorder);
    this.properties = properties;
    this.shared = shared;
  }

  @Override
  protected String applicationName() {
    return "pricing-service";
  }

  @Override
  protected List<Object> propertiesBeans() {
    return List.of(this.properties, this.shared);
  }

  @Override
  protected PricingSettings buildSnapshot() {
    return new PricingSettings(
        this.properties.getCurrency(),
        this.properties.getDiscountPercentage(),
        this.properties.isSurgePricingEnabled(),
        this.properties.getSurgeMultiplier(),
        this.shared.getBannerMessage(),
        this.shared.getEnvironmentLabel());
  }
}
