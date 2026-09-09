package com.example.config.commons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a short, non-reversible fingerprint of a secret value.
 *
 * <p>This exists so that a decrypted {@code {cipher}} value can be *proved* to have arrived
 * correctly without ever being exposed. Returning the secret itself from an inspection endpoint —
 * or logging it — would defeat the entire point of encrypting it at rest, so the snapshot carries
 * this fingerprint instead. An operator (or an acceptance test) compares it against the fingerprint
 * of the value they expect.
 *
 * <p>Truncated to 16 hex characters: enough to distinguish values in practice, short enough to read
 * in a log line, and not a full digest that could assist an offline attack on a low-entropy secret.
 */
public final class SecretFingerprint {

  private static final int FINGERPRINT_HEX_LENGTH = 16;

  private SecretFingerprint() {}

  /**
   * Returns {@code sha256:<16 hex chars>} for the given value, or {@code "absent"} when the value
   * is null or blank.
   */
  public static String of(String value) {
    if (value == null || value.isBlank()) {
      return "absent";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_HEX_LENGTH);
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandated by the JDK; this cannot happen on a conformant runtime.
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /** True when a {@code {cipher}} placeholder reached the client undecrypted. */
  public static boolean looksEncrypted(String value) {
    return value != null && value.startsWith("{cipher}");
  }
}
