package com.example.config.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the inventory service, served by the Config Server.
 *
 * <p><b>Setter-based JavaBean binding is mandatory here, not a style choice.</b> Spring Cloud's
 * {@code ConfigurationPropertiesRebinder} refreshes this bean by re-initialising the
 * <i>existing</i> instance through its setters. A {@code record} or any constructor-bound class is
 * re-created instead, so every reference injected before the refresh would keep stale values and
 * the whole live-refresh feature would appear to work intermittently.
 *
 * <p><b>Deliberately not annotated {@code @Validated}.</b> The rebinder rethrows after recording a
 * failure, so a constraint violation raised during rebind propagates out of {@code
 * ContextRefresher.refresh()} and {@code RefreshScopeRefreshedEvent} is never published — leaving
 * no opportunity to retain last-known-good or report the problem. Validation is therefore driven by
 * the snapshot provider, which validates this bean after rebinding and rejects the refresh without
 * breaking the chain. Immutability lives in the snapshot record, not here.
 */
@Component
@ConfigurationProperties(prefix = "inventory")
public class InventoryConfigProperties {

  @NotBlank private String warehouseCode;

  @Min(1)
  @Max(10_000)
  private int maxOrderQuantity;

  private boolean expressShippingEnabled;

  @Min(0)
  private int lowStockThreshold;

  /**
   * Stored in the configuration backend as a {@code {cipher}} value and decrypted by the Config
   * Server before it is served, so this client only ever sees plaintext and never holds the key.
   */
  @NotBlank private String downstreamApiKey;

  public String getWarehouseCode() {
    return this.warehouseCode;
  }

  public void setWarehouseCode(String warehouseCode) {
    this.warehouseCode = warehouseCode;
  }

  public int getMaxOrderQuantity() {
    return this.maxOrderQuantity;
  }

  public void setMaxOrderQuantity(int maxOrderQuantity) {
    this.maxOrderQuantity = maxOrderQuantity;
  }

  public boolean isExpressShippingEnabled() {
    return this.expressShippingEnabled;
  }

  public void setExpressShippingEnabled(boolean expressShippingEnabled) {
    this.expressShippingEnabled = expressShippingEnabled;
  }

  public int getLowStockThreshold() {
    return this.lowStockThreshold;
  }

  public String getDownstreamApiKey() {
    return this.downstreamApiKey;
  }

  public void setDownstreamApiKey(String downstreamApiKey) {
    this.downstreamApiKey = downstreamApiKey;
  }

  public void setLowStockThreshold(int lowStockThreshold) {
    this.lowStockThreshold = lowStockThreshold;
  }
}
