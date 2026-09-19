package com.example.config.inventory.controller;

import com.example.config.inventory.dto.ReservationRequest;
import com.example.config.inventory.dto.ReservationResponse;
import com.example.config.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Business endpoint whose behaviour is driven by live configuration. */
@RestController
@RequestMapping("/api/v1/inventory")
@Validated
@Tag(
    name = "Inventory Management",
    description = "Operations for inventory reservation and stock management")
public class InventoryController {

  private final InventoryService inventoryService;

  public InventoryController(InventoryService inventoryService) {
    this.inventoryService = inventoryService;
  }

  @PostMapping("/reservations")
  @Operation(
      summary = "Create an inventory reservation",
      description =
          "Validates requested quantity against dynamically configured thresholds and reserves"
              + " inventory.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Reservation successfully created",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ReservationResponse.class))),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or validation failure",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
            responseCode = "422",
            description = "Requested quantity exceeds active maximum limit",
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
  public ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
    return this.inventoryService.reserve(request);
  }
}
