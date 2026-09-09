package com.example.config.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test for the Git backend, against a real JGit repository.
 *
 * <p>Covers what unit tests cannot: that the Environment API really resolves per-application,
 * per-profile and per-label content from a Git repository, and that the endpoints are authenticated
 * and role-separated. Before this existed, the Git backend was only exercised by {@code
 * scripts/e2e-test.sh} against a running Docker stack.
 *
 * <p>Tests are explicitly ordered because they share one repository on disk, and the last one
 * mutates it. Relying on JUnit's default order would make the earlier assertions fail depending on
 * execution sequence — which is exactly the false failure this ordering prevents.
 *
 * <p>The Bus is disabled on purpose: this test is about the Git backend, and requiring a broker
 * would make it fail for an unrelated reason.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.cloud.bus.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
      "spring.cloud.config.server.git.default-label=main",
      "app.security.admin-password={noop}test-admin",
      "app.security.client-password={noop}test-client",
      "encrypt.key-store.location="
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitBackendIT {

  private static Path repoDir;

  // Constructed directly rather than injected: Spring Boot 4 no longer auto-registers a
  // TestRestTemplate bean for @SpringBootTest(webEnvironment = RANDOM_PORT).
  private final TestRestTemplate restTemplate = new TestRestTemplate();

  @Value("${local.server.port}")
  private int port;

  @BeforeAll
  static void createRepository() throws IOException, GitAPIException {
    // toRealPath() is REQUIRED, not tidiness. Spring Cloud Config rejects a file:// URI whose
    // path contains a symbolic link ("Path component must not be a symbolic link: /var") as a
    // deliberate security check. On macOS /var is a symlink to /private/var and
    // createTempDirectory returns /var/folders/..., so the raw temp path is always rejected.
    repoDir = Files.createTempDirectory("config-repo-it").toRealPath();
    write(
        "application.yml",
        """
        demo:
          shared:
            banner-message: "from git"
            environment-label: "it"
        """);
    write(
        "inventory-service.yml",
        """
        inventory:
          warehouse-code: "WH-IT-01"
          max-order-quantity: 250
        """);
    write(
        "inventory-service-dev.yml",
        """
        inventory:
          max-order-quantity: 999
        """);
    try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
      commitAll(git, "seed");
    }
  }

  private static void write(String name, String content) throws IOException {
    Files.writeString(repoDir.resolve(name), content);
  }

  private static void commitAll(Git git, String message) throws GitAPIException {
    git.add().addFilepattern(".").call();
    git.commit().setMessage(message).setAuthor("IT", "it@example.com").setSign(false).call();
  }

  @DynamicPropertySource
  static void gitUri(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.config.server.git.uri", () -> "file://" + repoDir);
  }

  private String url(String path) {
    return "http://localhost:" + this.port + path;
  }

  private ResponseEntity<String> fetch(String path, String user, String password) {
    return this.restTemplate.withBasicAuth(user, password).getForEntity(url(path), String.class);
  }

  @Test
  @Order(1)
  @DisplayName("serves application-specific and shared properties for a label")
  void servesPropertiesForLabel() {
    ResponseEntity<String> response =
        fetch("/inventory-service/default/main", "config-client", "test-client");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("WH-IT-01").contains("from git");
  }

  @Test
  @Order(2)
  @DisplayName("a profile overlay takes precedence over the base file")
  void profileOverlayWins() {
    ResponseEntity<String> response =
        fetch("/inventory-service/dev/main", "config-client", "test-client");

    assertThat(response.getBody()).contains("999");
  }

  @Test
  @Order(3)
  @DisplayName("an unknown label is rejected rather than silently served from another")
  void unknownLabelIsRejected() {
    ResponseEntity<String> response =
        fetch("/inventory-service/default/no-such-label", "config-client", "test-client");

    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
  }

  @Test
  @Order(4)
  @DisplayName("the Environment API requires authentication")
  void requiresAuthentication() {
    ResponseEntity<String> anonymous =
        this.restTemplate.getForEntity(url("/inventory-service/default/main"), String.class);

    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Order(5)
  @DisplayName("a client credential cannot reach the admin-only endpoints")
  void clientCannotUseAdminEndpoints() {
    ResponseEntity<String> forbidden =
        this.restTemplate
            .withBasicAuth("config-client", "test-client")
            .postForEntity(url("/monitor"), "path=inventory-service.yml", String.class);

    assertThat(forbidden.getStatusCode())
        .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
  }

  @Test
  @Order(99)
  @DisplayName("a file:// repository serves the WORKING TREE, so uncommitted edits are visible")
  void fileUriServesTheWorkingTree() throws Exception {
    // This contradicts the usual mental model, and an earlier version of this test asserted the
    // opposite and failed. With a file: URI the Config Server uses the repository directory AS
    // its working directory - it never clones - so a GET reads whatever is on disk right now.
    // Editing a file without committing DOES change what the server serves.
    //
    // Committing still matters, but for a different reason: the post-commit hook is what fires
    // the /monitor notification, so without a commit nothing tells running clients to refresh.
    // What the server SERVES and what triggers PROPAGATION are two separate concerns.
    //
    // Ordered last because it mutates the shared repository.
    write("inventory-service.yml", "inventory:\n  warehouse-code: \"WH-WORKING-TREE\"\n");

    ResponseEntity<String> uncommitted =
        fetch("/inventory-service/default/main", "config-client", "test-client");
    assertThat(uncommitted.getBody())
        .as("a file:// backend reads the working tree, not the committed tree")
        .contains("WH-WORKING-TREE");

    try (Git git = Git.open(repoDir.toFile())) {
      commitAll(git, "commit the working-tree change");
    }

    ResponseEntity<String> committed =
        fetch("/inventory-service/default/main", "config-client", "test-client");
    assertThat(committed.getBody()).contains("WH-WORKING-TREE");
  }
}
