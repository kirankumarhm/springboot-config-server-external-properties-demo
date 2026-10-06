package com.example.config.inventory.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.config.inventory.config.InventoryProperties;
import com.example.config.inventory.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract: response body, security headers and RFC 9457 errors. */
@WebMvcTest(
    controllers = InventoryController.class,
    properties = {
      "inventory.warehouse-code=WH-TEST",
      "inventory.max-order-quantity=400",
      "inventory.express-shipping-enabled=true",
      "inventory.low-stock-threshold=15"
    })
@Import({InventoryProperties.class, SecurityConfig.class})
class InventoryControllerTest {

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("GET /api/v1/inventory/config returns only the inventory properties")
  void returnsTheInventoryProperties() throws Exception {
    this.mvc
        .perform(get("/api/v1/inventory/config"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"warehouseCode":"WH-TEST","maxOrderQuantity":400,
                     "expressShippingEnabled":true,"lowStockThreshold":15}
                    """,
                    JsonCompareMode.STRICT));
  }

  @Test
  @DisplayName("every response carries the security headers")
  void sendsSecurityHeaders() throws Exception {
    this.mvc
        .perform(get("/api/v1/inventory/config"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
  }

  @Test
  @DisplayName("an unknown path under the API returns a 404 ProblemDetail")
  void unknownPathIsProblemDetail() throws Exception {
    this.mvc
        .perform(get("/api/v1/inventory/nope"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Resource not found"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  @DisplayName("a write method returns a 405 ProblemDetail")
  void postIsMethodNotAllowed() throws Exception {
    this.mvc
        .perform(post("/api/v1/inventory/config"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.title").value("Method not allowed"));
  }

  @Test
  @DisplayName("health checks are open, so Docker and Kubernetes probes work")
  void healthIsNotDeniedBySecurity() throws Exception {
    // No actuator in this slice test, so the request reaches MVC and 404s - but is NOT 403.
    this.mvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("paths outside the API and the docs are denied")
  void otherPathsAreDenied() throws Exception {
    this.mvc.perform(get("/internal")).andExpect(status().isForbidden());
  }
}
