package com.example.config.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.example.config.inventory.domain.InventorySettings;
import com.example.config.inventory.dto.ReservationRequest;
import com.example.config.inventory.dto.ReservationResponse;
import com.example.config.inventory.exception.OrderQuantityExceededException;
import com.example.config.inventory.refresh.ConfigSnapshotStatus;
import com.example.config.inventory.refresh.InventorySettingsProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the config-driven business rules.
 *
 * <p>No Spring context: the service takes its configuration through a provider, so behaviour under
 * any configuration generation is testable with a plain stub.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

  @Mock private InventorySettingsProvider settingsProvider;

  private void givenSettings(InventorySettings settings) {
    given(this.settingsProvider.get()).willReturn(settings);
    // status() is only reached on the success path. The rejection test throws before it is
    // called, so a strict stub would be flagged UnnecessaryStubbing; scope leniency to this
    // one stub rather than weakening strictness for the whole class.
    lenient()
        .when(this.settingsProvider.status())
        .thenReturn(
            new ConfigSnapshotStatus(
                "inventory-service",
                7L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.APPLIED,
                null,
                1L,
                0L,
                List.of()));
  }

  private static InventorySettings settings(int maxQty, boolean express, int lowStock) {
    return new InventorySettings("WH-BLR-01", maxQty, express, lowStock, "banner", "local");
  }

  @Test
  @DisplayName("standard shipping when the express feature flag is off")
  void standardShippingWhenFlagOff() {
    givenSettings(settings(500, false, 25));

    ReservationResponse response =
        new InventoryService(this.settingsProvider).reserve(new ReservationRequest("SKU-1", 10));

    assertThat(response.expressEligible()).isFalse();
    assertThat(response.shippingMode()).isEqualTo("STANDARD");
    assertThat(response.warehouseCode()).isEqualTo("WH-BLR-01");
    assertThat(response.configVersion()).isEqualTo(7L);
  }

  @Test
  @DisplayName("express shipping as soon as the flag flips - no restart involved")
  void expressShippingWhenFlagOn() {
    givenSettings(settings(500, true, 25));

    ReservationResponse response =
        new InventoryService(this.settingsProvider).reserve(new ReservationRequest("SKU-1", 10));

    assertThat(response.expressEligible()).isTrue();
    assertThat(response.shippingMode()).isEqualTo("EXPRESS");
  }

  @Test
  @DisplayName("quantity at the configured limit is accepted")
  void acceptsQuantityAtLimit() {
    givenSettings(settings(500, false, 25));

    ReservationResponse response =
        new InventoryService(this.settingsProvider).reserve(new ReservationRequest("SKU-1", 500));

    assertThat(response.quantity()).isEqualTo(500);
    assertThat(response.appliedMaxOrderQuantity()).isEqualTo(500);
  }

  @Test
  @DisplayName("quantity above the configured limit is rejected with the limit in the error")
  void rejectsQuantityAboveLimit() {
    givenSettings(settings(500, false, 25));
    InventoryService service = new InventoryService(this.settingsProvider);

    assertThatThrownBy(() -> service.reserve(new ReservationRequest("SKU-1", 501)))
        .isInstanceOf(OrderQuantityExceededException.class)
        .hasMessageContaining("501")
        .hasMessageContaining("500");
  }

  @Test
  @DisplayName("raising the limit makes a previously rejected request succeed")
  void raisingLimitAllowsPreviouslyRejectedRequest() {
    givenSettings(settings(1000, false, 25));

    ReservationResponse response =
        new InventoryService(this.settingsProvider).reserve(new ReservationRequest("SKU-1", 501));

    assertThat(response.appliedMaxOrderQuantity()).isEqualTo(1000);
  }

  @Test
  @DisplayName("low stock warning is driven by the configured threshold")
  void lowStockWarningFollowsThreshold() {
    givenSettings(settings(500, false, 25));
    InventoryService service = new InventoryService(this.settingsProvider);

    assertThat(service.reserve(new ReservationRequest("SKU-1", 26)).lowStockWarning()).isTrue();
    assertThat(service.reserve(new ReservationRequest("SKU-1", 25)).lowStockWarning()).isFalse();
  }

  @Test
  @DisplayName("the snapshot is read exactly once per request")
  void readsSnapshotOncePerRequest() {
    givenSettings(settings(500, true, 25));

    new InventoryService(this.settingsProvider).reserve(new ReservationRequest("SKU-1", 10));

    // A second read could return a different generation mid-request and produce a response
    // that mixes two configurations.
    org.mockito.Mockito.verify(this.settingsProvider, org.mockito.Mockito.times(1)).get();
  }
}
