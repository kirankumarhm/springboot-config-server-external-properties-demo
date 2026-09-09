package com.example.config.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server, version A (Git backend).
 *
 * <p>Serves the Environment API from a Git repository, and converts repository push notifications
 * received on {@code /monitor} into refresh broadcasts over Spring Cloud Bus.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConfigServerApplication.class, args);
  }
}
