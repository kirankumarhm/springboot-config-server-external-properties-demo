package com.example.config.pricing.service;

import com.example.config.pricing.domain.PricingSettings;
import com.example.config.pricing.dto.QuoteResponse;
import com.example.config.pricing.exception.InvalidPricingRequestException;
import com.example.config.pricing.refresh.PricingSettingsProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/** Quote calculation driven by live configuration. */
@Service
public class PricingService {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final PricingSettingsProvider settingsProvider;

  public PricingService(PricingSettingsProvider settingsProvider) {
    this.settingsProvider = settingsProvider;
  }

  public QuoteResponse quote(String sku, BigDecimal basePrice) {
    if (sku == null || sku.isBlank()) {
      throw new InvalidPricingRequestException("sku must not be blank");
    }
    if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) < 0) {
      throw new InvalidPricingRequestException("basePrice must be greater than or equal to 0");
    }

    // One snapshot read for the whole calculation, so discount and surge always come from the
    // same configuration generation even if a refresh lands mid-request.
    PricingSettings settings = this.settingsProvider.get();

    BigDecimal discountAmount =
        basePrice.multiply(settings.discountPercentage()).divide(HUNDRED, 2, RoundingMode.HALF_UP);

    BigDecimal afterDiscount = basePrice.subtract(discountAmount);

    BigDecimal surgeAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    if (settings.surgePricingEnabled()) {
      surgeAmount =
          afterDiscount
              .multiply(settings.surgeMultiplier().subtract(BigDecimal.ONE))
              .setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal finalPrice = afterDiscount.add(surgeAmount).setScale(2, RoundingMode.HALF_UP);

    return new QuoteResponse(
        sku,
        settings.currency(),
        basePrice.setScale(2, RoundingMode.HALF_UP),
        settings.discountPercentage(),
        discountAmount,
        settings.surgePricingEnabled(),
        surgeAmount,
        finalPrice,
        this.settingsProvider.status().version());
  }
}
