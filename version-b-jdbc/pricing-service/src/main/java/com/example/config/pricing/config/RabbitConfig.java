package com.example.config.pricing.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Spring AMQP and Spring Cloud Bus.
 *
 * <p>Provides a descriptive {@link ConnectionNameStrategy} so that active connections appear with
 * human-readable service names (e.g. {@code pricing-service:8092}) in the RabbitMQ Management Web
 * UI.
 */
@Configuration
public class RabbitConfig {

  @Bean
  public ConnectionNameStrategy connectionNameStrategy(
      @Value("${spring.application.name:pricing-service}") String appName,
      @Value("${server.port:8080}") String port) {
    return connectionFactory -> appName + ":" + port;
  }
}
