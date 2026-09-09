package com.example.config.inventory.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound reservation request. Constructor binding is correct here — request DTOs never refresh.
 */
public record ReservationRequest(@NotBlank String sku, @Min(1) int quantity) {}
