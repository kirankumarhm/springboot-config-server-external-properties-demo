package com.example.config.pricing.api;

import java.math.BigDecimal;

/** Quote result, echoing the configuration generation that produced it. */
public record QuoteResponse(
    String sku,
    String currency,
    BigDecimal basePrice,
    BigDecimal discountPercentage,
    BigDecimal discountAmount,
    boolean surgeApplied,
    BigDecimal surgeAmount,
    BigDecimal finalPrice,
    long configVersion) {}
