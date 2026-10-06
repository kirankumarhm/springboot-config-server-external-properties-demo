package com.example.config.pricing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.config.pricing.config.PricingProperties;
import com.example.config.pricing.config.SecurityConfig;
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
    controllers = PricingController.class,
    properties = {
      "pricing.currency=INR",
      "pricing.discount-percentage=10.0",
      "pricing.surge-pricing-enabled=true",
      "pricing.surge-multiplier=2.5"
    })
@Import({PricingProperties.class, SecurityConfig.class})
class PricingControllerTest {

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("GET /api/v1/pricing/config returns only the pricing properties")
  void returnsThePricingProperties() throws Exception {
    this.mvc
        .perform(get("/api/v1/pricing/config"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"currency":"INR","discountPercentage":10.0,
                     "surgePricingEnabled":true,"surgeMultiplier":2.5}
                    """,
                    JsonCompareMode.STRICT));
  }

  @Test
  @DisplayName("every response carries the security headers")
  void sendsSecurityHeaders() throws Exception {
    this.mvc
        .perform(get("/api/v1/pricing/config"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
  }

  @Test
  @DisplayName("an unknown path under the API returns a 404 ProblemDetail")
  void unknownPathIsProblemDetail() throws Exception {
    this.mvc
        .perform(get("/api/v1/pricing/nope"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Resource not found"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  @DisplayName("a write method returns a 405 ProblemDetail")
  void postIsMethodNotAllowed() throws Exception {
    this.mvc
        .perform(post("/api/v1/pricing/config"))
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
