package com.example.config.pricing.controller;

import com.example.config.pricing.config.PricingProperties;
import com.example.config.pricing.dto.PricingConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The service's API: the pricing configuration it is currently using. */
@RestController
@RequestMapping("/api/v1/pricing")
@Tag(name = "Pricing configuration")
public class PricingController {

  private final PricingProperties properties;

  public PricingController(PricingProperties properties) {
    this.properties = properties;
  }

  @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Get the current pricing configuration",
      description =
          "Returns the pricing.* properties served by the Config Server. After a change in the"
              + " configuration backend, the new values appear here within seconds.")
  @ApiResponse(responseCode = "200", description = "The configuration currently in use")
  @ApiResponse(
      responseCode = "500",
      description = "Unexpected server error",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  public PricingConfigResponse config() {
    return new PricingConfigResponse(
        this.properties.getCurrency(),
        this.properties.getDiscountPercentage(),
        this.properties.isSurgePricingEnabled(),
        this.properties.getSurgeMultiplier());
  }
}
