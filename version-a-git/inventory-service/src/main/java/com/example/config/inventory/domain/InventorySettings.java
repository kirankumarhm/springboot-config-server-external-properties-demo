package com.example.config.inventory.domain;

/**
 * Immutable snapshot of the inventory service's effective configuration.
 *
 * <p>Contains configuration values only — no version or timestamp. That is what makes record {@code
 * equals} a meaningful "did anything actually change?" test, which the provider uses to
 * short-circuit a refresh that changed nothing. Version and timestamp live in {@code
 * ConfigSnapshotStatus}.
 */
public record InventorySettings(
    String warehouseCode,
    int maxOrderQuantity,
    boolean expressShippingEnabled,
    int lowStockThreshold,
    String bannerMessage,
    String environmentLabel) {}
