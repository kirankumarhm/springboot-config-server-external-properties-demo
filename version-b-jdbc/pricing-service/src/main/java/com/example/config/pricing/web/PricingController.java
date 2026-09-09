package com.example.config.pricing.web;

import com.example.config.commons.ConfigRefreshAuditor;
import com.example.config.commons.ConfigSnapshotStatus;
import com.example.config.pricing.api.QuoteResponse;
import com.example.config.pricing.provider.PricingSettingsProvider;
import com.example.config.pricing.service.PricingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Business and configuration-inspection endpoints for the pricing service. */
@RestController
public class PricingController {

  private final PricingService pricingService;
  private final PricingSettingsProvider provider;
  private final ConfigRefreshAuditor auditor;

  public PricingController(
      PricingService pricingService,
      PricingSettingsProvider provider,
      ConfigRefreshAuditor auditor) {
    this.pricingService = pricingService;
    this.provider = provider;
    this.auditor = auditor;
  }

  @GetMapping("/api/v1/pricing/quotes/{sku}")
  QuoteResponse quote(
      @PathVariable String sku, @RequestParam(defaultValue = "1000.00") BigDecimal basePrice) {
    return this.pricingService.quote(sku, basePrice);
  }

  @GetMapping("/api/v1/config/snapshot")
  Map<String, Object> snapshot() {
    ConfigSnapshotStatus status = this.provider.status();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("application", status.application());
    body.put("version", status.version());
    body.put("appliedAt", status.appliedAt().toString());
    body.put("lastOutcome", status.lastOutcome());
    body.put("refreshAttempts", status.refreshAttempts());
    body.put("rejectedCount", status.rejectedCount());
    body.put("lastChangedKeys", status.lastChangedKeys());
    if (status.lastFailureReason() != null) {
      body.put("lastFailureReason", status.lastFailureReason());
    }
    body.put("settings", this.provider.get());
    return body;
  }

  @GetMapping("/api/v1/config/history")
  List<Map<String, Object>> history() {
    return this.auditor.history();
  }
}
