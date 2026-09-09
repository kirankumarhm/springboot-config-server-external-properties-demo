package com.example.config.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory service: a Config Server client that refreshes live.
 *
 * <p>Scans {@code com.example.config} so the shared refresh plumbing in {@code
 * config-client-commons} is picked up alongside this module's own components.
 */
@SpringBootApplication(scanBasePackages = "com.example.config")
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }
}
