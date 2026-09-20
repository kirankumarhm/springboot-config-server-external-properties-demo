package com.example.config.server.config;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Spring AMQP and Spring Cloud Bus.
 *
 * <p>Provides a descriptive {@link ConnectionNameStrategy} so that active connections appear with
 * human-readable service names (e.g. {@code config-server:8888}) in the RabbitMQ Management Web UI.
 */
@Configuration
public class RabbitConfig {

  @Bean
  public ConnectionNameStrategy connectionNameStrategy(
      @Value("${spring.application.name:config-server}") String appName,
      @Value("${server.port:8888}") String port) {
    return connectionFactory -> appName + ":" + port;
  }
}
