package com.example.config.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 documentation configuration for the Inventory Service.
 *
 * <p>Exposes interactive Swagger UI at {@code /swagger-ui.html} and raw OpenAPI definitions at
 * {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI inventoryOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Inventory Service API")
                .description(
                    "Production-grade microservice demonstrating zero-downtime dynamic"
                        + " configuration refresh with Spring Cloud Config Server and Spring Cloud"
                        + " Bus.")
                .version("1.0.0")
                .contact(new Contact().name("Architecture Team").email("architecture@example.com"))
                .license(
                    new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")))
        .servers(
            List.of(
                new Server().url("http://localhost:8081").description("Local Development Server"),
                new Server()
                    .url("http://inventory-service:8081")
                    .description("Docker Compose Service")));
  }
}
