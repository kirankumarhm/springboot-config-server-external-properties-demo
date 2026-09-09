package com.example.config.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.config.server.SecurityConfig.ConfigServerCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the credential holder binds and defaults sensibly. */
class ConfigServerCredentialsTest {

  @Test
  @DisplayName("usernames default, passwords do not - a missing password must fail loudly")
  void defaultsAreSafe() {
    ConfigServerCredentials credentials = new ConfigServerCredentials();

    assertThat(credentials.getAdminUsername()).isEqualTo("config-admin");
    assertThat(credentials.getClientUsername()).isEqualTo("config-client");
    // No default password: an unset CONFIG_ADMIN_PASSWORD must not silently yield a working,
    // guessable credential.
    assertThat(credentials.getAdminPassword()).isNull();
    assertThat(credentials.getClientPassword()).isNull();
  }

  @Test
  @DisplayName("all four values are settable, so they can come from the environment")
  void valuesAreSettable() {
    ConfigServerCredentials credentials = new ConfigServerCredentials();
    credentials.setAdminUsername("admin");
    credentials.setAdminPassword("{bcrypt}$2a$10$abc");
    credentials.setClientUsername("client");
    credentials.setClientPassword("{noop}secret");

    assertThat(credentials.getAdminUsername()).isEqualTo("admin");
    assertThat(credentials.getAdminPassword()).isEqualTo("{bcrypt}$2a$10$abc");
    assertThat(credentials.getClientUsername()).isEqualTo("client");
    assertThat(credentials.getClientPassword()).isEqualTo("{noop}secret");
  }
}
