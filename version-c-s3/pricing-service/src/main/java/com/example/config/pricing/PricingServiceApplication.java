package com.example.config.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pricing service: the second Config Server client, used to prove broadcast scoping.
 *
 * <p>Default component scanning is enough: this service owns its refresh plumbing under {@code
 * com.example.config.pricing}, so there is no shared library package to reach out to.
 */
@SpringBootApplication
public class PricingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PricingServiceApplication.class, args);
  }
}
