package com.example.config.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** Quote result, echoing the configuration generation that produced it. */
@Schema(description = "Pricing quote calculation response")
public record QuoteResponse(
    @Schema(description = "Stock Keeping Unit identifier", example = "SKU-ITEM-42") String sku,
    @Schema(description = "Configured pricing currency code", example = "INR") String currency,
    @Schema(description = "Base price before discounts or surge pricing", example = "1000.00")
        BigDecimal basePrice,
    @Schema(description = "Discount percentage applied", example = "10.0")
        BigDecimal discountPercentage,
    @Schema(description = "Calculated discount amount", example = "100.00")
        BigDecimal discountAmount,
    @Schema(description = "Whether surge pricing multiplier was applied", example = "false")
        boolean surgeApplied,
    @Schema(description = "Calculated surge amount", example = "0.00") BigDecimal surgeAmount,
    @Schema(description = "Final effective calculated price", example = "900.00")
        BigDecimal finalPrice,
    @Schema(description = "Configuration snapshot version used to calculate quote", example = "1")
        long configVersion) {}
