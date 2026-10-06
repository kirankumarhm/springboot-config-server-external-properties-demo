// Package settings reads the service's own startup settings from environment variables and
// rejects bad values immediately, so a typo fails at startup instead of at the first request.
package settings

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Application is this service's name: the {application} part of the Config Server URL, and the
// name Spring Cloud Bus events are addressed to.
const Application = "go-service"

// Settings is everything the service needs to start.
type Settings struct {
	Port         int
	ConfigServer ConfigServer
	RabbitMQ     RabbitMQ
}

// ConfigServer locates and authenticates against Spring Cloud Config Server.
type ConfigServer struct {
	URL, Username, Password, Label, Profile string
	Timeout                                 time.Duration
}

// RabbitMQ locates the broker that carries Spring Cloud Bus.
type RabbitMQ struct {
	Host, Username, Password string
	Port                     int
}

// AMQPURL is the broker address with credentials, for the AMQP client only. Never log it.
func (r RabbitMQ) AMQPURL() string {
	u := url.URL{Scheme: "amqp", User: url.UserPassword(r.Username, r.Password), Host: fmt.Sprintf("%s:%d", r.Host, r.Port), Path: "/"}
	return u.String()
}

// Load reads the settings from getenv (os.Getenv in production).
func Load(getenv func(string) string) (Settings, error) {
	get := func(key, fallback string) string {
		if value := getenv(key); value != "" {
			return value
		}
		return fallback
	}
	integer := func(key string, fallback, min, max int) (int, error) {
		raw := get(key, strconv.Itoa(fallback))
		value, err := strconv.Atoi(raw)
		if err != nil || value < min || value > max {
			return 0, fmt.Errorf("%s must be an integer between %d and %d, got %q", key, min, max, raw)
		}
		return value, nil
	}

	port, err := integer("PORT", 8095, 1, 65535)
	if err != nil {
		return Settings{}, err
	}
	timeoutMs, err := integer("CONFIG_TIMEOUT_MS", 5000, 100, 60000)
	if err != nil {
		return Settings{}, err
	}
	rabbitPort, err := integer("RABBITMQ_PORT", 5673, 1, 65535)
	if err != nil {
		return Settings{}, err
	}
	serverURL := strings.TrimSuffix(get("CONFIG_SERVER_URL", "http://localhost:8898"), "/")
	parsed, err := url.Parse(serverURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return Settings{}, fmt.Errorf("CONFIG_SERVER_URL must be an http(s) URL, got %q", serverURL)
	}
	if parsed.User != nil {
		return Settings{}, fmt.Errorf("CONFIG_SERVER_URL must not contain credentials; use CONFIG_CLIENT_USERNAME / CONFIG_CLIENT_PASSWORD")
	}

	return Settings{
		Port: port,
		ConfigServer: ConfigServer{
			URL:      serverURL,
			Username: get("CONFIG_CLIENT_USERNAME", "config-client"),
			Password: get("CONFIG_CLIENT_PASSWORD", "client-secret"),
			Label:    get("CONFIG_LABEL", "main"),
			Profile:  get("CONFIG_PROFILE", "default"),
			Timeout:  time.Duration(timeoutMs) * time.Millisecond,
		},
		RabbitMQ: RabbitMQ{
			Host:     get("RABBITMQ_HOST", "localhost"),
			Port:     rabbitPort,
			Username: get("RABBITMQ_USERNAME", "guest"),
			Password: get("RABBITMQ_PASSWORD", "guest"),
		},
	}, nil
}
