package com.example.config.inventory.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Checks the constraints on {@link InventoryProperties}.
 *
 * <ul>
 *   <li><b>At startup</b> an invalid value stops the application: it must not serve traffic on
 *       configuration it cannot honour.
 *   <li><b>After a refresh</b> an invalid value is logged as an ERROR so operators see it at once.
 *       The application keeps running; fix the value in the configuration backend.
 * </ul>
 *
 * <p>{@code RefreshScopeRefreshedEvent} is used rather than {@code EnvironmentChangeEvent} because
 * it is published only after the properties have been rebound.
 */
@Component
public class InventoryPropertiesValidator {

  private static final Logger log = LoggerFactory.getLogger(InventoryPropertiesValidator.class);

  private final InventoryProperties properties;
  private final Validator validator;

  public InventoryPropertiesValidator(InventoryProperties properties, Validator validator) {
    this.properties = properties;
    this.validator = validator;
  }

  @EventListener(ApplicationStartedEvent.class)
  public void validateAtStartup() {
    String violations = violations();
    if (!violations.isEmpty()) {
      throw new InvalidConfigurationException("Invalid inventory configuration: " + violations);
    }
  }

  @EventListener(RefreshScopeRefreshedEvent.class)
  public void validateAfterRefresh() {
    String violations = violations();
    if (!violations.isEmpty()) {
      log.error("Refreshed inventory configuration is invalid: {}", violations);
    }
  }

  private String violations() {
    Set<ConstraintViolation<InventoryProperties>> found = this.validator.validate(this.properties);
    return found.stream()
        .map(v -> "inventory." + v.getPropertyPath() + " " + v.getMessage())
        .sorted()
        .collect(Collectors.joining("; "));
  }
}
