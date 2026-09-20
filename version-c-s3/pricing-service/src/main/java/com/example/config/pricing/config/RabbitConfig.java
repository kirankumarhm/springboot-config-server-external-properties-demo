package com.example.config.pricing.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Spring AMQP and Spring Cloud Bus.
 *
 * <p>Provides a clean {@link ConnectionNameStrategy} so that active connections appear with clean
 * instance names (e.g. {@code pricing-service-1}, {@code pricing-service-2}) in the RabbitMQ
 * Management Web UI.
 */
@Configuration
public class RabbitConfig {

  @Bean
  public ConnectionNameStrategy connectionNameStrategy(
      @Value("${spring.application.name:pricing-service}") String appName,
      @Value("${APP_INDEX:}") String appIndex) {
    return connectionFactory -> {
      if ("8083".equals(appIndex) || "8093".equals(appIndex) || "8103".equals(appIndex) || "2".equals(appIndex)) {
        return appName + "-2";
      }
      return appName + "-1";
    };
  }
}
