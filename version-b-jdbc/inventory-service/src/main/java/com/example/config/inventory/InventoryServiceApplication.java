package com.example.config.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory service: a Config Server client that refreshes live.
 *
 * <p>Default component scanning is enough: this service owns its refresh plumbing under {@code
 * com.example.config.inventory}, so there is no shared library package to reach out to.
 */
@SpringBootApplication
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }
}
