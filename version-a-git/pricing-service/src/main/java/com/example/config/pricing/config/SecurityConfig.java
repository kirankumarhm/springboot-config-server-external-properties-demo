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
 * HTTP security for the service.
 *
 * <p>Stateless, with strict security headers on every response. CSRF protection is disabled because
 * the API is read-only, cookie-less and session-less. The only paths reachable are the business
 * endpoint, the OpenAPI/Swagger documentation and the health checks used by Docker and Kubernetes;
 * anything else is denied. This chain also guards the separate management port.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  // Swagger UI needs inline scripts and styles; everything else is same-origin only.
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
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/pricing/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll());
    return http.build();
  }
}
