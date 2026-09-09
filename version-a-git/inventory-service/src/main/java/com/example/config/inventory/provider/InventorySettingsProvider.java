package com.example.config.inventory.provider;

import com.example.config.commons.AbstractConfigurationSnapshotProvider;
import com.example.config.commons.ConfigRefreshAuditor;
import com.example.config.commons.ConfigRefreshMetrics;
import com.example.config.commons.EnvironmentChangeKeyRecorder;
import com.example.config.commons.SecretFingerprint;
import com.example.config.commons.SharedConfigProperties;
import com.example.config.inventory.config.InventoryConfigProperties;
import com.example.config.inventory.domain.InventorySettings;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Publishes validated, immutable {@link InventorySettings} snapshots to business code. */
@Component
public class InventorySettingsProvider
    extends AbstractConfigurationSnapshotProvider<InventorySettings> {

  private final InventoryConfigProperties properties;
  private final SharedConfigProperties shared;

  public InventorySettingsProvider(
      InventoryConfigProperties properties,
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
    return "inventory-service";
  }

  @Override
  protected List<Object> propertiesBeans() {
    return List.of(this.properties, this.shared);
  }

  @Override
  protected InventorySettings buildSnapshot() {
    return new InventorySettings(
        this.properties.getWarehouseCode(),
        this.properties.getMaxOrderQuantity(),
        this.properties.isExpressShippingEnabled(),
        this.properties.getLowStockThreshold(),
        this.shared.getBannerMessage(),
        this.shared.getEnvironmentLabel(),
        SecretFingerprint.of(this.properties.getDownstreamApiKey()));
  }
}
