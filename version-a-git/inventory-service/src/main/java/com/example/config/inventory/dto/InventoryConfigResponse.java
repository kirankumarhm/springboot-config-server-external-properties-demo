package com.example.config.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The inventory configuration currently in use, as returned by the API. */
@Schema(description = "The inventory configuration currently in use")
public record InventoryConfigResponse(
    @Schema(description = "Warehouse that fulfils orders", example = "WH-BLR-01")
        String warehouseCode,
    @Schema(description = "Largest quantity allowed in one order", example = "400")
        int maxOrderQuantity,
    @Schema(description = "Whether express shipping is offered", example = "true")
        boolean expressShippingEnabled,
    @Schema(description = "Stock level at which an item counts as low", example = "15")
        int lowStockThreshold) {}
