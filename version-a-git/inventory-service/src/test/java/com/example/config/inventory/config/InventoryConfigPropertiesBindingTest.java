package com.example.config.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that the externalised keys actually bind, and that the bean stays mutable.
 *
 * <p>The mutability assertion is not pedantry: setter-based binding is what allows Spring Cloud's
 * rebinder to refresh this bean in place. If someone converted it to a record, binding here would
 * still pass while live refresh silently stopped working — so both properties are asserted.
 */
class InventoryConfigPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InventoryConfigProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              org.springframework.boot.autoconfigure.AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Test
  @DisplayName("kebab-case keys bind to camelCase properties")
  void bindsKebabCaseKeys() {
    this.runner
        .withPropertyValues(
            "inventory.warehouse-code=WH-BLR-01",
            "inventory.max-order-quantity=500",
            "inventory.express-shipping-enabled=true",
            "inventory.low-stock-threshold=25",
            "inventory.downstream-api-key=plaintext-after-server-side-decryption")
        .run(
            context -> {
              InventoryConfigProperties properties =
                  context.getBean(InventoryConfigProperties.class);
              assertThat(properties.getWarehouseCode()).isEqualTo("WH-BLR-01");
              assertThat(properties.getMaxOrderQuantity()).isEqualTo(500);
              assertThat(properties.isExpressShippingEnabled()).isTrue();
              assertThat(properties.getLowStockThreshold()).isEqualTo(25);
            });
  }

  @Test
  @DisplayName("an out-of-range value still BINDS - it is the provider that rejects it")
  void invalidValueStillBinds() {
    // The class is intentionally not @Validated: a violation raised during rebind would abort
    // the refresh chain before RefreshScopeRefreshedEvent is published, destroying the
    // last-known-good guarantee. Binding therefore succeeds and the provider decides.
    this.runner
        .withPropertyValues(
            "inventory.warehouse-code=WH-BLR-01",
            "inventory.max-order-quantity=99999",
            "inventory.low-stock-threshold=25",
            "inventory.downstream-api-key=plaintext-after-server-side-decryption")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(InventoryConfigProperties.class).getMaxOrderQuantity())
                  .isEqualTo(99999);
            });
  }

  @Test
  @DisplayName("the bean exposes a setter for every field, so it can be rebound in place")
  void beanIsRebindable() {
    InventoryConfigProperties properties = new InventoryConfigProperties();

    properties.setWarehouseCode("WH-NEW");
    properties.setMaxOrderQuantity(750);
    properties.setExpressShippingEnabled(true);
    properties.setLowStockThreshold(40);
    properties.setDownstreamApiKey("k");

    assertThat(properties.getWarehouseCode()).isEqualTo("WH-NEW");
    assertThat(properties.getMaxOrderQuantity()).isEqualTo(750);
    assertThat(properties.isExpressShippingEnabled()).isTrue();
    assertThat(properties.getLowStockThreshold()).isEqualTo(40);
    assertThat(InventoryConfigProperties.class.isRecord())
        .as("a record cannot be rebound in place by ConfigurationPropertiesRebinder")
        .isFalse();
  }
}
