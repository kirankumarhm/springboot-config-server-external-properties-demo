package com.example.config.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** The pricing configuration currently in use, as returned by the API. */
@Schema(description = "The pricing configuration currently in use")
public record PricingConfigResponse(
    @Schema(description = "ISO 4217 currency code for all prices", example = "INR") String currency,
    @Schema(description = "Discount applied to every price, in percent", example = "10.0")
        BigDecimal discountPercentage,
    @Schema(description = "Whether surge pricing is switched on", example = "false")
        boolean surgePricingEnabled,
    @Schema(description = "Price multiplier while surge pricing is on", example = "2.5")
        BigDecimal surgeMultiplier) {}
