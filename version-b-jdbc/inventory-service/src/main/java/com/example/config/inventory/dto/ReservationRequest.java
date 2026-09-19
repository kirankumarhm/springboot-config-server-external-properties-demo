package com.example.config.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound reservation request. Constructor binding is correct here — request DTOs never refresh.
 */
@Schema(description = "Inbound reservation request payload")
public record ReservationRequest(
    @Schema(description = "Stock Keeping Unit identifier", example = "SKU-ITEM-42") @NotBlank
        String sku,
    @Schema(description = "Quantity to reserve", example = "5", minimum = "1") @Min(1)
        int quantity) {}
