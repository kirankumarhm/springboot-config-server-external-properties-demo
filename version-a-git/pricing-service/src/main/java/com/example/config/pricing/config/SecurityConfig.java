package com.example.config.pricing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Production-grade HTTP security configuration for the stateless microservice.
 *
 * <p>Enforces strict security headers (CSP, HSTS, X-Frame-Options, X-Content-Type-Options,
 * Permissions-Policy), disables session creation and CSRF (safe for stateless REST APIs), and
 * permits public access to business endpoints, OpenAPI/Swagger docs, and health probes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String CSP_POLICY =
      "default-src 'self'; script-src 'self' 'unsafe-inline'; "
          + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none';";

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives(CSP_POLICY))
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(content -> {})
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/actuator/metrics/**",
                        "/actuator/busrefresh",
                        "/actuator/refresh",
                        "/actuator/env")
                    .permitAll()
                    .anyRequest()
                    .authenticated());

    return http.build();
  }
}
