package com.example.config.server.change;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Maps an S3 object key to the application it configures.
 *
 * <p>Deliberately exact, unlike the Git path's {@code PropertyPathEndpoint}, which cannot know
 * where an application name ends and a profile suffix begins and therefore strips dash-separated
 * segments and broadcasts to every candidate — a commit to {@code inventory-service-dev.yml}
 * produces three destinations. Here the set of application names is known, so the profile suffix is
 * resolved against it and exactly one destination is produced.
 */
@Component
public class S3ObjectKeyApplicationMapper {

  private static final List<String> CONFIG_EXTENSIONS =
      List.of(".yml", ".yaml", ".properties", ".json");

  private final String keyPrefix;
  private final List<String> knownApplications;

  public S3ObjectKeyApplicationMapper(
      @Value("${app.config-change.s3.key-prefix:}") String keyPrefix,
      @Value("${app.known-applications}") List<String> knownApplications) {
    this.keyPrefix = keyPrefix;
    this.knownApplications = knownApplications;
  }

  /** True if the key looks like a configuration object rather than unrelated bucket content. */
  public boolean isConfigObject(String key) {
    if (!this.keyPrefix.isEmpty() && !key.startsWith(this.keyPrefix)) {
      return false;
    }
    return CONFIG_EXTENSIONS.stream().anyMatch(key::endsWith);
  }

  /**
   * Resolves the broadcast destination for a key.
   *
   * @return the application name, {@code "*"} for shared configuration, or empty if unmappable
   */
  public Optional<String> toApplication(String key) {
    String fileName = key.substring(key.lastIndexOf('/') + 1);

    String withoutExtension = fileName;
    for (String extension : CONFIG_EXTENSIONS) {
      if (withoutExtension.endsWith(extension)) {
        withoutExtension = withoutExtension.substring(0, fileName.length() - extension.length());
        break;
      }
    }
    final String stem = withoutExtension;

    // application.yml / application-dev.yml apply to every client.
    if ("application".equals(stem) || stem.startsWith("application-")) {
      return Optional.of("*");
    }

    // Exact match first: inventory-service.yml
    if (this.knownApplications.contains(stem)) {
      return Optional.of(stem);
    }

    // Then a profile-suffixed form: inventory-service-dev.yml -> inventory-service.
    // Longest match wins, so an application whose own name contains a dash is handled correctly.
    return this.knownApplications.stream()
        .filter(app -> stem.startsWith(app + "-"))
        .max((a, b) -> Integer.compare(a.length(), b.length()));
  }
}
