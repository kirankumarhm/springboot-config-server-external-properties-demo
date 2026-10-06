package com.example.config.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 description of the service: Swagger UI at {@code /swagger-ui.html}, the raw document at
 * {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI inventoryOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Inventory Service API")
                .description(
                    "Returns the inventory configuration this service is currently using. The"
                        + " values come from Spring Cloud Config Server and change live, without"
                        + " a restart, when the configuration backend changes.")
                .version("1.0.0")
                .license(
                    new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
  }
}
