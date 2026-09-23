package com.example.config.inventory.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.config.inventory.domain.InventorySettings;
import com.example.config.inventory.dto.ReservationRequest;
import com.example.config.inventory.dto.ReservationResponse;
import com.example.config.inventory.exception.OrderQuantityExceededException;
import com.example.config.inventory.refresh.ConfigRefreshAuditor;
import com.example.config.inventory.refresh.ConfigSnapshotStatus;
import com.example.config.inventory.refresh.InventorySettingsProvider;
import com.example.config.inventory.service.InventoryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer tests: HTTP contract, ProblemDetail errors, and the snapshot inspection endpoint. */
@WebMvcTest(controllers = {InventoryController.class, ConfigInspectionController.class})
class InventoryWebMvcTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private InventoryService inventoryService;
  @MockitoBean private InventorySettingsProvider settingsProvider;
  @MockitoBean private ConfigRefreshAuditor auditor;

  @Test
  @DisplayName("POST /reservations returns the reservation and the config generation that made it")
  void createsReservation() throws Exception {
    given(this.inventoryService.reserve(new ReservationRequest("SKU-1", 10)))
        .willReturn(
            new ReservationResponse(
                "res-1", "SKU-1", 10, "WH-BLR-01", true, "EXPRESS", false, 500, 4L));

    this.mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-1\",\"quantity\":10}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shippingMode").value("EXPRESS"))
        .andExpect(jsonPath("$.warehouseCode").value("WH-BLR-01"))
        .andExpect(jsonPath("$.configVersion").value(4));
  }

  @Test
  @DisplayName("exceeding the configured limit yields RFC 9457 ProblemDetail with 422")
  void exceededQuantityReturnsProblemDetail() throws Exception {
    willThrow(new OrderQuantityExceededException(501, 500))
        .given(this.inventoryService)
        .reserve(new ReservationRequest("SKU-1", 501));

    this.mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-1\",\"quantity\":501}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Order quantity exceeded"))
        .andExpect(jsonPath("$.type").value("urn:problem:order-quantity-exceeded"))
        .andExpect(jsonPath("$.requested").value(501))
        .andExpect(jsonPath("$.allowed").value(500));
  }

  @Test
  @DisplayName("an invalid request body yields 400 with field-level detail")
  void invalidBodyReturns400() throws Exception {
    this.mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"\",\"quantity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  @DisplayName("GET /config/snapshot exposes version, outcome and effective settings")
  void exposesSnapshot() throws Exception {
    given(this.settingsProvider.get())
        .willReturn(new InventorySettings("WH-BLR-01", 500, true, 25, "banner", "local"));
    given(this.settingsProvider.status())
        .willReturn(
            new ConfigSnapshotStatus(
                "inventory-service",
                4L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.APPLIED,
                null,
                9L,
                0L,
                List.of("inventory.max-order-quantity")));

    this.mockMvc
        .perform(get("/api/v1/config/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("inventory-service"))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.lastOutcome").value("APPLIED"))
        .andExpect(jsonPath("$.settings.maxOrderQuantity").value(500))
        .andExpect(jsonPath("$.lastChangedKeys[0]").value("inventory.max-order-quantity"));
  }

  @Test
  @DisplayName("a rejected refresh surfaces lastFailureReason in the snapshot response")
  void exposesFailureReasonWhenRejected() throws Exception {
    given(this.settingsProvider.get())
        .willReturn(new InventorySettings("WH-BLR-01", 500, true, 25, "banner", "local"));
    given(this.settingsProvider.status())
        .willReturn(
            new ConfigSnapshotStatus(
                "inventory-service",
                4L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.REJECTED,
                "maxOrderQuantity must be less than or equal to 10000",
                10L,
                1L,
                List.of()));

    this.mockMvc
        .perform(get("/api/v1/config/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejectedCount").value(1))
        .andExpect(jsonPath("$.lastFailureReason").exists());
  }

  @Test
  @DisplayName("GET /config/history returns the audit records")
  void exposesHistory() throws Exception {
    given(this.auditor.history())
        .willReturn(List.of(Map.of("outcome", "APPLIED", "version", 4, "changedKeys", List.of())));

    this.mockMvc
        .perform(get("/api/v1/config/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].outcome").value("APPLIED"));
  }
}
