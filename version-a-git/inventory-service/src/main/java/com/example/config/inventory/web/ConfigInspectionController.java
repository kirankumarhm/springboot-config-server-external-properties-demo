package com.example.config.inventory.web;

import com.example.config.inventory.domain.InventorySettings;
import com.example.config.inventory.provider.InventorySettingsProvider;
import com.example.config.inventory.refresh.ConfigRefreshAuditor;
import com.example.config.inventory.refresh.ConfigSnapshotStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class ConfigInspectionController {

  private final InventorySettingsProvider provider;
  private final ConfigRefreshAuditor auditor;

  public ConfigInspectionController(
      InventorySettingsProvider provider, ConfigRefreshAuditor auditor) {
    this.provider = provider;
    this.auditor = auditor;
  }

  @GetMapping("/snapshot")
  Map<String, Object> snapshot() {
    InventorySettings settings = this.provider.get();
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
  List<Map<String, Object>> history() {
    return this.auditor.history();
  }
}
