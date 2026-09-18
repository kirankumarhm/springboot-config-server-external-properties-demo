package com.example.config.inventory.refresh;

/** Thrown when configuration fails validation at startup, so the application fails fast. */
public class ConfigurationValidationException extends RuntimeException {

  public ConfigurationValidationException(String message) {
    super(message);
  }
}
