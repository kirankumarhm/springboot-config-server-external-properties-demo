package com.example.config.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code inventory.*} properties served by the Config Server for {@code inventory-service}.
 *
 * <p><b>Setters, not a record.</b> On a refresh, Spring Cloud updates THIS object through its
 * setters, so everything holding a reference to it sees the new values without a restart. A record
 * (or constructor binding) cannot be updated in place and would keep the startup values.
 *
 * <p><b>Constraints, but deliberately no {@code @Validated}.</b> With {@code @Validated}, a bad
 * value arriving in a refresh makes Spring Cloud's rebinder throw, which aborts the refresh for the
 * whole application. The constraints are checked by {@link InventoryPropertiesValidator} instead.
 */
@Component
@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {

  @NotBlank private String warehouseCode;

  @Min(1)
  @Max(10_000)
  private int maxOrderQuantity;

  private boolean expressShippingEnabled;

  @Min(0)
  private int lowStockThreshold;

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

  public void setLowStockThreshold(int lowStockThreshold) {
    this.lowStockThreshold = lowStockThreshold;
  }
}
