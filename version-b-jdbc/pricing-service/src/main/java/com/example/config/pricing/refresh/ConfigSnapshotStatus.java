package com.example.config.pricing.refresh;

import java.time.Instant;
import java.util.List;

/**
 * Observable state of the configuration snapshot held by a provider.
 *
 * <p>Version and timestamp deliberately live here rather than inside the snapshot record, so that
 * the snapshot itself contains only configuration values. That makes {@code equals} on the snapshot
 * a meaningful "did anything actually change?" test, which is what the no-op short-circuit relies
 * on.
 *
 * @param application owning application name
 * @param version monotonically increasing, incremented only when values actually changed
 * @param appliedAt when the current snapshot was adopted
 * @param lastOutcome APPLIED, NO_CHANGE or REJECTED
 * @param lastFailureReason validation failure detail, or {@code null} if the last attempt was fine
 * @param refreshAttempts total refresh attempts observed
 * @param rejectedCount attempts rejected by validation, where last-known-good was retained
 * @param lastChangedKeys property key NAMES from the last environment change; never values
 */
public record ConfigSnapshotStatus(
    String application,
    long version,
    Instant appliedAt,
    String lastOutcome,
    String lastFailureReason,
    long refreshAttempts,
    long rejectedCount,
    List<String> lastChangedKeys) {

  public static final String APPLIED = "APPLIED";
  public static final String NO_CHANGE = "NO_CHANGE";
  public static final String REJECTED = "REJECTED";
}
