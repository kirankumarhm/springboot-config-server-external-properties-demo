package com.example.config.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits one structured JSON audit line per refresh and retains a bounded in-memory history.
 *
 * <p>Records key NAMES only. Property values are never logged: the same refresh may carry a {@code
 * {cipher}} value that the Config Server has already decrypted.
 */
@Component
public class ConfigRefreshAuditor {

  private static final Logger log = LoggerFactory.getLogger(ConfigRefreshAuditor.class);
  private static final int HISTORY_LIMIT = 50;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Deque<Map<String, Object>> history = new ArrayDeque<>();

  public void record(
      String application,
      String trigger,
      String outcome,
      List<String> changedKeys,
      long version,
      String failureReason) {

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("event", "config.refresh");
    record.put("application", application);
    record.put("trigger", trigger);
    record.put("outcome", outcome);
    record.put("version", version);
    record.put("changedKeys", changedKeys);
    if (failureReason != null) {
      record.put("failureReason", failureReason);
    }
    record.put("timestamp", Instant.now().toString());

    synchronized (this.history) {
      this.history.addFirst(record);
      while (this.history.size() > HISTORY_LIMIT) {
        this.history.removeLast();
      }
    }

    try {
      String json = this.objectMapper.writeValueAsString(record);
      if (ConfigSnapshotStatus.REJECTED.equals(outcome)) {
        log.error("{}", json);
      } else {
        log.info("{}", json);
      }
    } catch (Exception ex) {
      log.warn("Could not serialise config refresh audit record: {}", ex.getMessage());
    }
  }

  /** Most recent audit records, newest first. */
  public List<Map<String, Object>> history() {
    synchronized (this.history) {
      return List.copyOf(this.history);
    }
  }
}
