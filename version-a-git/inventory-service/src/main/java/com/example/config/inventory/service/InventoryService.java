package com.example.config.inventory.service;

import com.example.config.inventory.api.ReservationRequest;
import com.example.config.inventory.api.ReservationResponse;
import com.example.config.inventory.domain.InventorySettings;
import com.example.config.inventory.exception.OrderQuantityExceededException;
import com.example.config.inventory.provider.InventorySettingsProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Business logic whose behaviour visibly changes when configuration changes.
 *
 * <p>A plain singleton, deliberately not {@code @RefreshScope}: nothing here is destroyed and
 * re-created on refresh, so a refresh causes no latency spike and cannot leak resources.
 */
@Service
public class InventoryService {

  private final InventorySettingsProvider settingsProvider;

  public InventoryService(InventorySettingsProvider settingsProvider) {
    this.settingsProvider = settingsProvider;
  }

  public ReservationResponse reserve(ReservationRequest request) {
    // Read the snapshot EXACTLY ONCE per request. Every decision below is then made against one
    // consistent set of values, so a refresh landing mid-request can never produce a response
    // that mixes old and new configuration.
    InventorySettings settings = this.settingsProvider.get();

    if (request.quantity() > settings.maxOrderQuantity()) {
      throw new OrderQuantityExceededException(request.quantity(), settings.maxOrderQuantity());
    }

    boolean expressEligible = settings.expressShippingEnabled();
    boolean lowStockWarning = request.quantity() > settings.lowStockThreshold();

    return new ReservationResponse(
        UUID.randomUUID().toString(),
        request.sku(),
        request.quantity(),
        settings.warehouseCode(),
        expressEligible,
        expressEligible ? "EXPRESS" : "STANDARD",
        lowStockWarning,
        settings.maxOrderQuantity(),
        this.settingsProvider.status().version());
  }
}
