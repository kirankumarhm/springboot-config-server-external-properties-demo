package com.example.config.pricing.config;

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
 * PricingProperties} object now holds the new value.
 */
class PricingPropertiesRefreshTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(ConfigurationPropertiesRebinderAutoConfiguration.class))
          .withUserConfiguration(PropertiesConfig.class)
          .withPropertyValues("pricing.surge-multiplier=1.5");

  @Test
  void refreshUpdatesTheExistingBeanInPlace() {
    this.runner.run(
        context -> {
          PricingProperties before = context.getBean(PricingProperties.class);
          assertThat(before.getSurgeMultiplier()).isEqualByComparingTo("1.5");

          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(
                  new MapPropertySource("changed", Map.of("pricing.surge-multiplier", "2.5")));
          // The rebinder only reacts to events from its own context, not the test wrapper.
          context.publishEvent(
              new EnvironmentChangeEvent(
                  context.getSourceApplicationContext(), Set.of("pricing.surge-multiplier")));

          assertThat(context.getBean(PricingProperties.class)).isSameAs(before);
          assertThat(before.getSurgeMultiplier()).isEqualByComparingTo("2.5");
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PricingProperties.class)
  static class PropertiesConfig {}
}
