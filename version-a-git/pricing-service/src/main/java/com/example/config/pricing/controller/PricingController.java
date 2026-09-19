package com.example.config.pricing.controller;

import com.example.config.pricing.dto.QuoteResponse;
import com.example.config.pricing.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Business endpoint for quoting prices driven by live configuration. */
@RestController
@RequestMapping("/api/v1/pricing")
@Validated
@Tag(
    name = "Pricing Calculation",
    description = "Operations for dynamic price quotes, discounts, and surge pricing")
public class PricingController {

  private final PricingService pricingService;

  public PricingController(PricingService pricingService) {
    this.pricingService = pricingService;
  }

  @GetMapping("/quotes/{sku}")
  @Operation(
      summary = "Calculate dynamic price quote for a SKU",
      description =
          "Calculates dynamic price quote based on centrally configured discount percentages,"
              + " surge multipliers, and currency.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Price quote calculated successfully",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = QuoteResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid SKU or basePrice parameter",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ProblemDetail.class)))
      })
  public QuoteResponse quote(
      @Parameter(description = "Stock Keeping Unit identifier", example = "SKU-ITEM-42")
          @PathVariable
          @NotBlank
          String sku,
      @Parameter(description = "Base price before discount and surge", example = "1000.00")
          @RequestParam(defaultValue = "1000.00")
          @PositiveOrZero
          BigDecimal basePrice) {
    return this.pricingService.quote(sku, basePrice);
  }
}
