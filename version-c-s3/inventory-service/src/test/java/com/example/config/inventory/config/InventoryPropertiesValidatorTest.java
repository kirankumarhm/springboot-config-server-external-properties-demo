package com.example.config.inventory.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InventoryPropertiesValidatorTest {

  private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  private final Validator validator = this.factory.getValidator();

  @AfterEach
  void close() {
    this.factory.close();
  }

  private static InventoryProperties properties(String warehouseCode, int maxOrderQuantity) {
    InventoryProperties properties = new InventoryProperties();
    properties.setWarehouseCode(warehouseCode);
    properties.setMaxOrderQuantity(maxOrderQuantity);
    properties.setLowStockThreshold(15);
    return properties;
  }

  @Test
  void validConfigurationStartsNormally() {
    InventoryPropertiesValidator check =
        new InventoryPropertiesValidator(properties("WH-1", 400), this.validator);
    assertThatCode(check::validateAtStartup).doesNotThrowAnyException();
  }

  @Test
  void invalidConfigurationStopsStartupAndNamesEveryProblem() {
    InventoryPropertiesValidator check =
        new InventoryPropertiesValidator(properties("", 99_999), this.validator);
    assertThatThrownBy(check::validateAtStartup)
        .isInstanceOf(InvalidConfigurationException.class)
        .hasMessageContaining("inventory.maxOrderQuantity")
        .hasMessageContaining("inventory.warehouseCode");
  }

  @Test
  void invalidConfigurationAfterARefreshIsLoggedNotThrown() {
    InventoryPropertiesValidator check =
        new InventoryPropertiesValidator(properties("", 99_999), this.validator);
    assertThatCode(check::validateAfterRefresh).doesNotThrowAnyException();
  }
}
