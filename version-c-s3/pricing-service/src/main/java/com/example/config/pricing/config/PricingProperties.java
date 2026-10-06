package com.example.config.pricing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code pricing.*} properties served by the Config Server for {@code pricing-service}.
 *
 * <p><b>Setters, not a record.</b> On a refresh, Spring Cloud updates THIS object through its
 * setters, so everything holding a reference to it sees the new values without a restart. A record
 * (or constructor binding) cannot be updated in place and would keep the startup values.
 *
 * <p><b>Constraints, but deliberately no {@code @Validated}.</b> With {@code @Validated}, a bad
 * value arriving in a refresh makes Spring Cloud's rebinder throw, which aborts the refresh for the
 * whole application. The constraints are checked by {@link PricingPropertiesValidator} instead.
 *
 * <p>Amounts are {@link BigDecimal}, never {@code double}, so values like 10.1 are exact.
 */
@Component
@ConfigurationProperties(prefix = "pricing")
public class PricingProperties {

  /** ISO 4217 currency code. */
  @NotNull
  @Pattern(regexp = "[A-Z]{3}")
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
