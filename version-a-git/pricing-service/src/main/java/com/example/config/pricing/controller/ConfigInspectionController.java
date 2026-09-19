package com.example.config.pricing.controller;

import com.example.config.pricing.domain.PricingSettings;
import com.example.config.pricing.refresh.ConfigRefreshAuditor;
import com.example.config.pricing.refresh.ConfigSnapshotStatus;
import com.example.config.pricing.refresh.PricingSettingsProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the effective configuration snapshot and refresh history.
 *
 * <p>This is the endpoint the acceptance tests assert against: {@code version} increments only when
 * values actually changed, so comparing it before and after a commit proves propagation without
 * relying on log scraping.
 */
@RestController
@RequestMapping("/api/v1/config")
@Tag(
    name = "Configuration Inspection",
    description =
        "Endpoints for inspecting live configuration snapshots, versions, and audit history")
public class ConfigInspectionController {

  private final PricingSettingsProvider provider;
  private final ConfigRefreshAuditor auditor;

  public ConfigInspectionController(
      PricingSettingsProvider provider, ConfigRefreshAuditor auditor) {
    this.provider = provider;
    this.auditor = auditor;
  }

  @GetMapping("/snapshot")
  @Operation(
      summary = "Get active configuration snapshot",
      description =
          "Returns current immutable configuration snapshot, version, applied timestamp, and"
              + " change metrics.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Configuration snapshot retrieved successfully"),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ProblemDetail.class)))
      })
  public Map<String, Object> snapshot() {
    PricingSettings settings = this.provider.get();
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
    body.put("settings", settings);
    return body;
  }

  @GetMapping("/history")
  @Operation(
      summary = "Get configuration refresh audit history",
      description =
          "Returns historical audit trail of all refresh events received via Spring Cloud Bus.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Audit trail retrieved successfully")
      })
  public List<Map<String, Object>> history() {
    return this.auditor.history();
  }
}
