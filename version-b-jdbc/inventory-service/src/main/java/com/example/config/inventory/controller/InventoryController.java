package com.example.config.inventory.controller;

import com.example.config.inventory.config.InventoryProperties;
import com.example.config.inventory.dto.InventoryConfigResponse;
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

/** The service's API: the inventory configuration it is currently using. */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory configuration")
public class InventoryController {

  private final InventoryProperties properties;

  public InventoryController(InventoryProperties properties) {
    this.properties = properties;
  }

  @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Get the current inventory configuration",
      description =
          "Returns the inventory.* properties served by the Config Server. After a change in the"
              + " configuration backend, the new values appear here within seconds.")
  @ApiResponse(responseCode = "200", description = "The configuration currently in use")
  @ApiResponse(
      responseCode = "500",
      description = "Unexpected server error",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  public InventoryConfigResponse config() {
    return new InventoryConfigResponse(
        this.properties.getWarehouseCode(),
        this.properties.getMaxOrderQuantity(),
        this.properties.isExpressShippingEnabled(),
        this.properties.getLowStockThreshold());
  }
}
