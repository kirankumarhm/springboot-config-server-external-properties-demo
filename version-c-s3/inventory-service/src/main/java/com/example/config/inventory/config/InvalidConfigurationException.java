package com.example.config.inventory.config;

/** Thrown at startup when the configuration served by the Config Server breaks a constraint. */
public class InvalidConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidConfigurationException(String message) {
    super(message);
  }
}
