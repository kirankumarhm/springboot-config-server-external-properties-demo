package com.example.config.pricing.exception;

/** Raised when a requested price calculation has invalid input parameters. */
public class InvalidPricingRequestException extends RuntimeException {

  public InvalidPricingRequestException(String message) {
    super(message);
  }
}
