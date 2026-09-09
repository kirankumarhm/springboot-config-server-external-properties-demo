package com.example.config.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.example.config.commons.ConfigSnapshotStatus;
import com.example.config.pricing.api.QuoteResponse;
import com.example.config.pricing.domain.PricingSettings;
import com.example.config.pricing.provider.PricingSettingsProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for quote calculation, including money-rounding behaviour. */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

  @Mock private PricingSettingsProvider settingsProvider;

  private PricingService givenSettings(PricingSettings settings) {
    given(this.settingsProvider.get()).willReturn(settings);
    // status() is only reached on the success path. The rejection test throws before it is
    // called, so a strict stub would be flagged UnnecessaryStubbing; scope leniency to this
    // one stub rather than weakening strictness for the whole class.
    lenient()
        .when(this.settingsProvider.status())
        .thenReturn(
            new ConfigSnapshotStatus(
                "pricing-service",
                3L,
                Instant.parse("2026-08-30T10:00:00Z"),
                ConfigSnapshotStatus.APPLIED,
                null,
                1L,
                0L,
                List.of()));
    return new PricingService(this.settingsProvider);
  }

  private static PricingSettings settings(String discount, boolean surge, String multiplier) {
    return new PricingSettings(
        "INR", new BigDecimal(discount), surge, new BigDecimal(multiplier), "banner", "local");
  }

  @Test
  @DisplayName("applies the configured discount")
  void appliesDiscount() {
    QuoteResponse quote =
        givenSettings(settings("10.0", false, "1.5")).quote("SKU-1", new BigDecimal("1000.00"));

    assertThat(quote.discountAmount()).isEqualByComparingTo("100.00");
    assertThat(quote.finalPrice()).isEqualByComparingTo("900.00");
    assertThat(quote.surgeApplied()).isFalse();
    assertThat(quote.surgeAmount()).isEqualByComparingTo("0.00");
    assertThat(quote.currency()).isEqualTo("INR");
    assertThat(quote.configVersion()).isEqualTo(3L);
  }

  @Test
  @DisplayName("a changed discount changes the quote on the very next request")
  void changedDiscountChangesQuote() {
    QuoteResponse quote =
        givenSettings(settings("25.0", false, "1.5")).quote("SKU-1", new BigDecimal("1000.00"));

    assertThat(quote.discountAmount()).isEqualByComparingTo("250.00");
    assertThat(quote.finalPrice()).isEqualByComparingTo("750.00");
  }

  @Test
  @DisplayName("surge pricing adds a component only when the flag is on")
  void surgeAppliedOnlyWhenEnabled() {
    QuoteResponse quote =
        givenSettings(settings("10.0", true, "1.5")).quote("SKU-1", new BigDecimal("1000.00"));

    // 1000 - 100 discount = 900; surge multiplier 1.5 adds 0.5 * 900 = 450
    assertThat(quote.surgeApplied()).isTrue();
    assertThat(quote.surgeAmount()).isEqualByComparingTo("450.00");
    assertThat(quote.finalPrice()).isEqualByComparingTo("1350.00");
  }

  @Test
  @DisplayName("a multiplier of exactly 1.0 adds nothing even when surge is enabled")
  void neutralMultiplierAddsNothing() {
    QuoteResponse quote =
        givenSettings(settings("10.0", true, "1.0")).quote("SKU-1", new BigDecimal("1000.00"));

    assertThat(quote.surgeAmount()).isEqualByComparingTo("0.00");
    assertThat(quote.finalPrice()).isEqualByComparingTo("900.00");
  }

  @Test
  @DisplayName("zero discount leaves the base price intact")
  void zeroDiscount() {
    QuoteResponse quote =
        givenSettings(settings("0.0", false, "1.5")).quote("SKU-1", new BigDecimal("999.99"));

    assertThat(quote.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(quote.finalPrice()).isEqualByComparingTo("999.99");
  }

  @Test
  @DisplayName("money is rounded HALF_UP to two decimal places, never left with float artefacts")
  void roundsHalfUpToTwoDecimals() {
    // 33.33% of 100.01 = 33.333333, must round to 33.33
    QuoteResponse quote =
        givenSettings(settings("33.33", false, "1.5")).quote("SKU-1", new BigDecimal("100.01"));

    assertThat(quote.discountAmount()).isEqualByComparingTo("33.33");
    assertThat(quote.discountAmount().scale()).isEqualTo(2);
    assertThat(quote.finalPrice().scale()).isEqualTo(2);
  }
}
