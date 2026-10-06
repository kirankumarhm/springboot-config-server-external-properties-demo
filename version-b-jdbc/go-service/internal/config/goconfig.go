// Package config loads go-service's configuration from Spring Cloud Config Server and holds the
// values currently in use.
package config

import (
	"fmt"
	"strconv"
	"strings"
)

// GoConfig is this service's go.* configuration, and the JSON body of its API.
type GoConfig struct {
	Greeting       string `json:"greeting"`
	FeatureEnabled bool   `json:"featureEnabled"`
	MaxItems       int    `json:"maxItems"`
}

// InvalidConfigurationError lists every problem found, so one fix-up covers them all.
type InvalidConfigurationError struct {
	Problems []string
}

func (e *InvalidConfigurationError) Error() string {
	return "invalid go-service configuration: " + strings.Join(e.Problems, "; ")
}

// toGoConfig validates the merged properties and keeps only the go.* keys.
//
// Values may arrive as strings (the JDBC backend stores everything as text) or as real booleans
// and numbers (the Git and S3 backends parse YAML), so both are accepted.
func toGoConfig(properties map[string]any) (GoConfig, error) {
	var problems []string
	text := func(key string) string {
		switch value := properties[key].(type) {
		case string:
			return value
		case nil:
			return ""
		default:
			return fmt.Sprint(value)
		}
	}

	greeting := text("go.greeting")
	if strings.TrimSpace(greeting) == "" {
		problems = append(problems, "go.greeting must be a non-empty string")
	}

	featureEnabled, err := strconv.ParseBool(text("go.feature-enabled"))
	if err != nil {
		problems = append(problems, "go.feature-enabled must be true or false")
	}

	maxItems, err := strconv.Atoi(text("go.max-items"))
	if err != nil || maxItems < 1 || maxItems > 1000 {
		problems = append(problems, "go.max-items must be an integer between 1 and 1000")
	}

	if len(problems) > 0 {
		return GoConfig{}, &InvalidConfigurationError{Problems: problems}
	}
	return GoConfig{Greeting: greeting, FeatureEnabled: featureEnabled, MaxItems: maxItems}, nil
}
