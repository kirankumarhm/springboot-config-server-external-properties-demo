package com.example.config.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication and authorisation for every Config Server endpoint.
 *
 * <p>Two principals with distinct roles: clients may only read the Environment API, while the admin
 * principal may also use {@code /encrypt}, {@code /decrypt} and {@code /monitor}. Stateless HTTP
 * Basic, since every caller is a service or a script rather than a browser.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        // No browser clients, so no session and no CSRF token to carry. Both /monitor and
        // /actuator/busrefresh are non-browser POSTs and would otherwise be blocked.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Probes must be reachable without credentials.
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // Repository push notifications.
                    .requestMatchers(HttpMethod.POST, "/monitor")
                    .hasRole("CONFIG_ADMIN")
                    // Cipher management and manual broadcast.
                    .requestMatchers("/encrypt/**", "/decrypt/**", "/actuator/busrefresh/**")
                    .hasRole("CONFIG_ADMIN")
                    .requestMatchers("/actuator/**")
                    .hasRole("CONFIG_ADMIN")
                    // Environment API: readable by clients and admins alike.
                    .anyRequest()
                    .hasAnyRole("CONFIG_CLIENT", "CONFIG_ADMIN"))
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    // Delegating encoder: accepts {noop} locally and {bcrypt}$2a$... in a deployed environment,
    // so the same code works without a rebuild.
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(ConfigServerCredentials credentials) {
    return new InMemoryUserDetailsManager(
        User.withUsername(credentials.getAdminUsername())
            .password(credentials.getAdminPassword())
            .roles("CONFIG_ADMIN")
            .build(),
        User.withUsername(credentials.getClientUsername())
            .password(credentials.getClientPassword())
            .roles("CONFIG_CLIENT")
            .build());
  }

  /** Credentials supplied from the environment; see application.yml for the bindings. */
  @Configuration
  @ConfigurationProperties(prefix = "app.security")
  public static class ConfigServerCredentials {

    private String adminUsername = "config-admin";
    private String adminPassword;
    private String clientUsername = "config-client";
    private String clientPassword;

    public String getAdminUsername() {
      return this.adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
      this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
      return this.adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
      this.adminPassword = adminPassword;
    }

    public String getClientUsername() {
      return this.clientUsername;
    }

    public void setClientUsername(String clientUsername) {
      this.clientUsername = clientUsername;
    }

    public String getClientPassword() {
      return this.clientPassword;
    }

    public void setClientPassword(String clientPassword) {
      this.clientPassword = clientPassword;
    }
  }
}
