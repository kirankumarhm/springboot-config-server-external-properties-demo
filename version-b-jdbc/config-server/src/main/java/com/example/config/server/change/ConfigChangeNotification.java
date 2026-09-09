package com.example.config.server.change;

import java.time.Instant;
import java.util.Set;

/**
 * What changed, normalised across every backend.
 *
 * @param applications application names to refresh; {@code "*"} means all applications
 * @param source which detector observed the change: pg-notify, jdbc-poll, s3-sqs, git-webhook
 * @param correlationId ties the detection to the resulting broadcast in the audit trail
 * @param detectedAt when the change was observed
 */
public record ConfigChangeNotification(
    Set<String> applications, String source, String correlationId, Instant detectedAt) {}
