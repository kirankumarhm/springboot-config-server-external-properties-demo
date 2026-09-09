package com.example.config.server.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the exactness that distinguishes this backend from the Git path.
 *
 * <p>The Git webhook receiver cannot know where an application name ends and a profile suffix
 * begins, so it strips dash-separated segments and broadcasts to every candidate. This mapper
 * resolves the suffix against the known application names, so it must produce exactly one
 * destination — that is what these tests pin down.
 */
class S3ObjectKeyApplicationMapperTest {

  private final S3ObjectKeyApplicationMapper mapper =
      new S3ObjectKeyApplicationMapper(
          "main/", List.of("inventory-service", "pricing-service", "inventory"));

  @ParameterizedTest
  @CsvSource({
    "main/inventory-service.yml,       inventory-service",
    "main/inventory-service.yaml,      inventory-service",
    "main/inventory-service.properties,inventory-service",
    "main/inventory-service.json,      inventory-service",
    "main/pricing-service.yml,         pricing-service",
  })
  @DisplayName("an application object maps to exactly that application")
  void mapsApplicationObject(String key, String expected) {
    assertThat(this.mapper.toApplication(key)).contains(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "main/inventory-service-dev.yml,  inventory-service",
    "main/inventory-service-prod.yml, inventory-service",
    "main/pricing-service-dev.yml,    pricing-service",
  })
  @DisplayName("a profile suffix is resolved against known names, not guessed")
  void resolvesProfileSuffixExactly(String key, String expected) {
    // The Git path would emit both "inventory-service" and "inventory" here.
    assertThat(this.mapper.toApplication(key)).contains(expected);
  }

  @Test
  @DisplayName("longest match wins, so an app whose own name contains a dash is not truncated")
  void longestMatchWins() {
    // "inventory" is also a known application, so a naive prefix match could pick it.
    assertThat(this.mapper.toApplication("main/inventory-service-dev.yml"))
        .contains("inventory-service");
    assertThat(this.mapper.toApplication("main/inventory-dev.yml")).contains("inventory");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"main/application.yml", "main/application-dev.yml", "main/application.properties"})
  @DisplayName("shared configuration broadcasts to all applications")
  void sharedConfigurationMapsToWildcard(String key) {
    assertThat(this.mapper.toApplication(key)).contains("*");
  }

  @Test
  @DisplayName("an unknown application name is not mapped rather than guessed at")
  void unknownApplicationIsNotMapped() {
    assertThat(this.mapper.toApplication("main/some-other-thing.yml")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "main/inventory-service.yml, true",
    "main/notes.txt,             false",
    "main/image.png,             false",
    "other/inventory-service.yml,false",
    "inventory-service.yml,      false",
  })
  @DisplayName("only configuration objects under the configured prefix are considered")
  void filtersByPrefixAndExtension(String key, boolean expected) {
    assertThat(this.mapper.isConfigObject(key)).isEqualTo(expected);
  }

  @Test
  @DisplayName("an empty prefix accepts objects at the bucket root")
  void emptyPrefixAcceptsRoot() {
    S3ObjectKeyApplicationMapper rootMapper =
        new S3ObjectKeyApplicationMapper("", List.of("inventory-service"));

    assertThat(rootMapper.isConfigObject("inventory-service.yml")).isTrue();
    assertThat(rootMapper.toApplication("inventory-service.yml")).contains("inventory-service");
  }
}
