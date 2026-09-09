package com.example.config.commons;

/** Thrown when configuration fails validation at startup, so the application fails fast. */
public class ConfigurationValidationException extends RuntimeException {

  public ConfigurationValidationException(String message) {
    super(message);
  }
}
