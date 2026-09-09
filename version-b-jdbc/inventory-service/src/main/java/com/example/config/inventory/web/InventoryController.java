package com.example.config.inventory.web;

import com.example.config.inventory.api.ReservationRequest;
import com.example.config.inventory.api.ReservationResponse;
import com.example.config.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Business endpoint whose behaviour is driven by live configuration. */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

  private final InventoryService inventoryService;

  public InventoryController(InventoryService inventoryService) {
    this.inventoryService = inventoryService;
  }

  @PostMapping("/reservations")
  ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
    return this.inventoryService.reserve(request);
  }
}
