package com.example.config.pricing.refresh;

/** Thrown when configuration fails validation at startup, so the application fails fast. */
public class ConfigurationValidationException extends RuntimeException {

  public ConfigurationValidationException(String message) {
    super(message);
  }
}
