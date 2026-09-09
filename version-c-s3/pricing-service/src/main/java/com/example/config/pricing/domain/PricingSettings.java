package com.example.config.pricing.domain;

import java.math.BigDecimal;

/** Immutable snapshot of the pricing service's effective configuration. Values only. */
public record PricingSettings(
    String currency,
    BigDecimal discountPercentage,
    boolean surgePricingEnabled,
    BigDecimal surgeMultiplier,
    String bannerMessage,
    String environmentLabel) {}
