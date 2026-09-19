package com.example.config.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled revision reconciler.
 *
 * <p>Deliberately does NOT define a second {@code DataSource} bean for the LISTEN connection. An
 * earlier version did, as a one-connection Hikari pool, and Boot's auto-configured {@code
 * JdbcTemplate} bound to it instead of the main pool. Since the listener holds that single
 * connection open indefinitely inside {@code getNotifications()}, every query by the reconciler and
 * the health endpoint then timed out waiting for a connection that would never be returned.
 *
 * <p>PostgresNotifyChangeDetector now opens one raw {@code DriverManager} connection instead. That
 * is a better fit regardless: the requirement is exactly one dedicated physical connection held for
 * the process lifetime, which is the opposite of what a pool is for.
 */
@Configuration
@EnableScheduling
public class NotificationDataSourceConfig {}
