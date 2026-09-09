package com.example.config.inventory.api;

/**
 * Reservation result. Echoes the configuration that shaped it, plus the snapshot version, so a test
 * can prove which configuration generation produced this response.
 */
public record ReservationResponse(
    String reservationId,
    String sku,
    int quantity,
    String warehouseCode,
    boolean expressEligible,
    String shippingMode,
    boolean lowStockWarning,
    int appliedMaxOrderQuantity,
    long configVersion) {}
