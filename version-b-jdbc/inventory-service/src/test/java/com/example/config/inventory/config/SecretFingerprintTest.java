package com.example.config.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the fingerprint is stable, non-reversible and never echoes the input. */
class SecretFingerprintTest {

  @Test
  @DisplayName("the same value always yields the same fingerprint")
  void isStable() {
    assertThat(SecretFingerprint.of("s3cr3t")).isEqualTo(SecretFingerprint.of("s3cr3t"));
  }

  @Test
  @DisplayName("different values yield different fingerprints")
  void isDiscriminating() {
    assertThat(SecretFingerprint.of("s3cr3t")).isNotEqualTo(SecretFingerprint.of("s3cr3u"));
  }

  @Test
  @DisplayName("the fingerprint never contains the value and is truncated")
  void neverEchoesTheValue() {
    String fingerprint = SecretFingerprint.of("super-secret-api-key");

    assertThat(fingerprint).doesNotContain("super-secret-api-key");
    assertThat(fingerprint).startsWith("sha256:");
    // 16 hex chars: readable in a log line, and not a full digest that would help an offline
    // attack against a low-entropy secret.
    assertThat(fingerprint).hasSize("sha256:".length() + 16);
  }

  @Test
  @DisplayName("a null or blank value reads 'absent' rather than hashing empty input")
  void absentValues() {
    assertThat(SecretFingerprint.of(null)).isEqualTo("absent");
    assertThat(SecretFingerprint.of("")).isEqualTo("absent");
    assertThat(SecretFingerprint.of("   ")).isEqualTo("absent");
  }

  @Test
  @DisplayName("an undecrypted {cipher} placeholder is detectable")
  void detectsUndecryptedCipher() {
    assertThat(SecretFingerprint.looksEncrypted("{cipher}AQBv7k")).isTrue();
    assertThat(SecretFingerprint.looksEncrypted("plaintext")).isFalse();
    assertThat(SecretFingerprint.looksEncrypted(null)).isFalse();
  }
}
