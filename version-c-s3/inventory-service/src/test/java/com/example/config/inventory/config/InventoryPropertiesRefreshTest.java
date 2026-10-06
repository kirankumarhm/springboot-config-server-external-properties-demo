package com.example.config.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * Proves the live-refresh mechanism without a Config Server: when a property changes and an {@link
 * EnvironmentChangeEvent} is published (which is what a Bus refresh does), the SAME {@link
 * InventoryProperties} object now holds the new value.
 */
class InventoryPropertiesRefreshTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(ConfigurationPropertiesRebinderAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfig.class)
          .withPropertyValues("inventory.max-order-quantity=400");

  @Test
  void refreshUpdatesTheExistingBeanInPlace() {
    this.runner.run(
        context -> {
          InventoryProperties before = context.getBean(InventoryProperties.class);
          assertThat(before.getMaxOrderQuantity()).isEqualTo(400);

          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(
                  new MapPropertySource("changed", Map.of("inventory.max-order-quantity", "750")));
          // The rebinder only reacts to events from its own context, not the test wrapper.
          context.publishEvent(
              new EnvironmentChangeEvent(
                  context.getSourceApplicationContext(), Set.of("inventory.max-order-quantity")));

          assertThat(context.getBean(InventoryProperties.class)).isSameAs(before);
          assertThat(before.getMaxOrderQuantity()).isEqualTo(750);
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InventoryProperties.class)
  static class PropertiesConfig {}
}
