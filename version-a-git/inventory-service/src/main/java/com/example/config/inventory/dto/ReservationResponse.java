package com.example.config.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reservation result. Echoes the configuration that shaped it, plus the snapshot version, so a test
 * can prove which configuration generation produced this response.
 */
@Schema(description = "Inventory reservation confirmation response")
public record ReservationResponse(
    @Schema(description = "Unique reservation identifier", example = "res-00000001")
        String reservationId,
    @Schema(description = "Stock Keeping Unit identifier", example = "SKU-ITEM-42") String sku,
    @Schema(description = "Quantity reserved", example = "5") int quantity,
    @Schema(description = "Fulfilling warehouse code", example = "WH-BLR-01") String warehouseCode,
    @Schema(description = "Whether the order is eligible for express shipping", example = "true")
        boolean expressEligible,
    @Schema(description = "Assigned shipping mode", example = "EXPRESS") String shippingMode,
    @Schema(description = "Whether the remaining stock is below the threshold", example = "false")
        boolean lowStockWarning,
    @Schema(
            description = "Maximum order quantity enforced by active configuration",
            example = "500")
        int appliedMaxOrderQuantity,
    @Schema(
            description = "Configuration snapshot version used to process the request",
            example = "1")
        long configVersion) {}
