package com.example.config.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Pricing service: the second Config Server client, used to prove broadcast scoping. */
@SpringBootApplication(scanBasePackages = "com.example.config")
public class PricingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PricingServiceApplication.class, args);
  }
}
