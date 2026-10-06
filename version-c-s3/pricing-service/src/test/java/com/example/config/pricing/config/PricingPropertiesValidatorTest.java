package com.example.config.pricing.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PricingPropertiesValidatorTest {

  private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  private final Validator validator = this.factory.getValidator();

  @AfterEach
  void close() {
    this.factory.close();
  }

  private static PricingProperties properties(String currency, String surgeMultiplier) {
    PricingProperties properties = new PricingProperties();
    properties.setCurrency(currency);
    properties.setDiscountPercentage(new BigDecimal("10.0"));
    properties.setSurgeMultiplier(new BigDecimal(surgeMultiplier));
    return properties;
  }

  @Test
  void validConfigurationStartsNormally() {
    PricingPropertiesValidator check =
        new PricingPropertiesValidator(properties("INR", "2.5"), this.validator);
    assertThatCode(check::validateAtStartup).doesNotThrowAnyException();
  }

  @Test
  void invalidConfigurationStopsStartupAndNamesEveryProblem() {
    PricingPropertiesValidator check =
        new PricingPropertiesValidator(properties("rupees", "9.0"), this.validator);
    assertThatThrownBy(check::validateAtStartup)
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("pricing.currency")
        .hasMessageContaining("pricing.surgeMultiplier");
  }

  @Test
  void invalidConfigurationAfterARefreshIsLoggedNotThrown() {
    PricingPropertiesValidator check =
        new PricingPropertiesValidator(properties("rupees", "9.0"), this.validator);
    assertThatCode(check::validateAfterRefresh).doesNotThrowAnyException();
  }
}
