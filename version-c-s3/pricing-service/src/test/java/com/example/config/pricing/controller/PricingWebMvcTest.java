package com.example.config.pricing.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.config.pricing.domain.PricingSettings;
import com.example.config.pricing.dto.QuoteResponse;
import com.example.config.pricing.exception.GlobalExceptionHandler;
import com.example.config.pricing.refresh.ConfigRefreshAuditor;
import com.example.config.pricing.refresh.ConfigSnapshotStatus;
import com.example.config.pricing.refresh.PricingSettingsProvider;
import com.example.config.pricing.service.PricingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer tests for the pricing and config inspection endpoints. */
@WebMvcTest(controllers = {PricingController.class, ConfigInspectionController.class})
@Import(GlobalExceptionHandler.class)
class PricingWebMvcTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PricingService pricingService;
  @MockitoBean private PricingSettingsProvider settingsProvider;
  @MockitoBean private ConfigRefreshAuditor auditor;

  @Test
  @DisplayName("GET a quote returns the priced result and the config generation used")
  void returnsQuote() throws Exception {
    given(this.pricingService.quote("SKU-1", new BigDecimal("1000.00")))
        .willReturn(
            new QuoteResponse(
                "SKU-1",
                "INR",
                new BigDecimal("1000.00"),
                new BigDecimal("10.0"),
                new BigDecimal("100.00"),
                false,
                new BigDecimal("0.00"),
                new BigDecimal("900.00"),
                3L));

    this.mockMvc
        .perform(get("/api/v1/pricing/quotes/SKU-1").param("basePrice", "1000.00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("INR"))
        .andExpect(jsonPath("$.finalPrice").value(900.00))
        .andExpect(jsonPath("$.configVersion").value(3));
  }

  @Test
  @DisplayName("basePrice defaults when the parameter is omitted")
  void basePriceDefaults() throws Exception {
    given(this.pricingService.quote("SKU-2", new BigDecimal("1000.00")))
        .willReturn(
            new QuoteResponse(
                "SKU-2",
                "INR",
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                new BigDecimal("0.00"),
                false,
                new BigDecimal("0.00"),
                new BigDecimal("1000.00"),
                3L));

    this.mockMvc
        .perform(get("/api/v1/pricing/quotes/SKU-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basePrice").value(1000.00));
  }

  @Test
  @DisplayName("GET /config/snapshot exposes the effective pricing configuration")
  void exposesSnapshot() throws Exception {
    given(this.settingsProvider.get())
        .willReturn(
            new PricingSettings(
                "INR", new BigDecimal("10.0"), false, new BigDecimal("1.5"), "banner", "local"));
    given(this.settingsProvider.status())
        .willReturn(
            new ConfigSnapshotStatus(
                "pricing-service",
                3L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.APPLIED,
                null,
                4L,
                0L,
                List.of("pricing.discount-percentage")));

    this.mockMvc
        .perform(get("/api/v1/config/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("pricing-service"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.settings.currency").value("INR"))
        .andExpect(jsonPath("$.lastChangedKeys[0]").value("pricing.discount-percentage"));
  }

  @Test
  @DisplayName("GET /config/history returns the audit records")
  void exposesHistory() throws Exception {
    given(this.auditor.history())
        .willReturn(
            List.of(Map.of("outcome", "NO_CHANGE", "version", 3, "changedKeys", List.of())));

    this.mockMvc
        .perform(get("/api/v1/config/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].outcome").value("NO_CHANGE"));
  }

  @Test
  @DisplayName("negative basePrice yields validation error with ProblemDetail 400")
  void negativeBasePriceReturnsBadRequest() throws Exception {
    this.mockMvc
        .perform(get("/api/v1/pricing/quotes/SKU-1").param("basePrice", "-50.00"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Constraint violation"));
  }

  @Test
  @DisplayName("rejected configuration snapshot surfaces failure reason")
  void exposesFailureReasonWhenRejected() throws Exception {
    given(this.settingsProvider.get())
        .willReturn(
            new PricingSettings(
                "INR", new BigDecimal("10.0"), false, new BigDecimal("1.5"), "banner", "local"));
    given(this.settingsProvider.status())
        .willReturn(
            new ConfigSnapshotStatus(
                "pricing-service",
                3L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.REJECTED,
                "discountPercentage must be between 0 and 100",
                4L,
                1L,
                List.of()));

    this.mockMvc
        .perform(get("/api/v1/config/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejectedCount").value(1))
        .andExpect(jsonPath("$.lastFailureReason").exists());
  }
}
