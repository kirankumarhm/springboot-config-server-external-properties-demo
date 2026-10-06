package com.example.config.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Pricing service: a Spring Boot client of the Config Server. */
@SpringBootApplication
public class PricingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PricingServiceApplication.class, args);
  }
}
