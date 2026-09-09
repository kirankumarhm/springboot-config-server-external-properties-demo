package com.example.config.pricing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the pricing service.
 *
 * <p>Setter-based binding and no {@code @Validated}, for the same two reasons documented on the
 * inventory service's equivalent class: the rebinder needs setters to refresh in place, and
 * validation must not be able to abort the refresh chain.
 */
@Component
@ConfigurationProperties(prefix = "pricing")
public class PricingConfigProperties {

  @Size(min = 3, max = 3)
  private String currency;

  @NotNull
  @DecimalMin("0.0")
  @DecimalMax("90.0")
  private BigDecimal discountPercentage;

  private boolean surgePricingEnabled;

  @NotNull
  @DecimalMin("1.0")
  @DecimalMax("5.0")
  private BigDecimal surgeMultiplier;

  public String getCurrency() {
    return this.currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getDiscountPercentage() {
    return this.discountPercentage;
  }

  public void setDiscountPercentage(BigDecimal discountPercentage) {
    this.discountPercentage = discountPercentage;
  }

  public boolean isSurgePricingEnabled() {
    return this.surgePricingEnabled;
  }

  public void setSurgePricingEnabled(boolean surgePricingEnabled) {
    this.surgePricingEnabled = surgePricingEnabled;
  }

  public BigDecimal getSurgeMultiplier() {
    return this.surgeMultiplier;
  }

  public void setSurgeMultiplier(BigDecimal surgeMultiplier) {
    this.surgeMultiplier = surgeMultiplier;
  }
}
