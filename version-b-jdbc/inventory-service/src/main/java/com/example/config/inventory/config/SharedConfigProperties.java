package com.example.config.inventory.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration served to every client from the shared {@code application.yml} in the config
 * repository. Changing a key here refreshes all clients; changing an application-specific file
 * refreshes only that one.
 *
 * <p>Each client owns its own binding of these keys: there is no shared library, so the two
 * services are independently deployable and neither can break the other by changing its view of the
 * shared file.
 *
 * <p>Setter-based JavaBean binding, deliberately. See {@code InventorySettingsProvider} and note
 * the absence of {@code @Validated}: validation is driven by the provider so that a bad value
 * cannot abort the refresh chain.
 */
@Component
@ConfigurationProperties(prefix = "demo.shared")
public class SharedConfigProperties {

  @NotBlank private String bannerMessage = "unset";

  @NotBlank private String environmentLabel = "unset";

  public String getBannerMessage() {
    return this.bannerMessage;
  }

  public void setBannerMessage(String bannerMessage) {
    this.bannerMessage = bannerMessage;
  }

  public String getEnvironmentLabel() {
    return this.environmentLabel;
  }

  public void setEnvironmentLabel(String environmentLabel) {
    this.environmentLabel = environmentLabel;
  }
}
