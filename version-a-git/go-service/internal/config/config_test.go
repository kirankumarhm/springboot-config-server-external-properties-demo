package config

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"example.com/go-service/internal/settings"
	"github.com/Piszmog/cloudconfigclient/v2"
)

func source(name string, values map[string]any) cloudconfigclient.PropertySource {
	return cloudconfigclient.PropertySource{Name: name, Source: values}
}

func TestMostSpecificSourceWinsAndOnlyGoKeysAreKept(t *testing.T) {
	store := NewStore(func() ([]cloudconfigclient.PropertySource, error) {
		return []cloudconfigclient.PropertySource{
			source("go-service.yml", map[string]any{"go.greeting": "Hello", "go.feature-enabled": true, "go.max-items": 50.0}),
			source("application.yml", map[string]any{"go.max-items": 1.0, "demo.shared.environment-label": "x"}),
		}, nil
	})
	if err := store.Load(); err != nil {
		t.Fatal(err)
	}
	if want := (GoConfig{Greeting: "Hello", FeatureEnabled: true, MaxItems: 50}); store.Current() != want {
		t.Fatalf("got %+v, want %+v", store.Current(), want)
	}
}

func TestStringValuesFromTheJdbcBackend(t *testing.T) {
	got, err := toGoConfig(map[string]any{"go.greeting": "Hi", "go.feature-enabled": "false", "go.max-items": "7"})
	if err != nil || got != (GoConfig{Greeting: "Hi", FeatureEnabled: false, MaxItems: 7}) {
		t.Fatalf("got %+v, %v", got, err)
	}
}

func TestInvalidValuesNameEveryProblem(t *testing.T) {
	_, err := toGoConfig(map[string]any{"go.greeting": " ", "go.feature-enabled": "yes", "go.max-items": "0"})
	var invalid *InvalidConfigurationError
	if !errors.As(err, &invalid) || len(invalid.Problems) != 3 {
		t.Fatalf("got %v", err)
	}
}

func TestFailedRefreshKeepsTheValuesInUse(t *testing.T) {
	maxItems := 50.0
	store := NewStore(func() ([]cloudconfigclient.PropertySource, error) {
		if maxItems == 0 {
			return nil, errors.New("Config Server unreachable")
		}
		return []cloudconfigclient.PropertySource{
			source("go-service.yml", map[string]any{"go.greeting": "Hi", "go.feature-enabled": true, "go.max-items": maxItems}),
		}, nil
	})
	if err := store.Load(); err != nil {
		t.Fatal(err)
	}
	maxItems = 99
	store.Refresh("test")
	if store.Current().MaxItems != 99 {
		t.Fatalf("refresh not applied: %+v", store.Current())
	}
	maxItems = 0
	store.Refresh("test")
	if store.Current().MaxItems != 99 {
		t.Fatalf("failed refresh replaced the values: %+v", store.Current())
	}
}

func TestFetcherUsesBasicAuthAndTheLabel(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, _ := r.BasicAuth()
		if r.URL.Path != "/go-service/default/main" || user != "config-client" || pass != "secret" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`{"name":"go-service","propertySources":[{"name":"go-service.yml","source":{"go.greeting":"Hello","go.feature-enabled":true,"go.max-items":50}}]}`))
	}))
	defer server.Close()

	cfg := settings.ConfigServer{URL: server.URL, Username: "config-client", Password: "secret", Label: "main", Profile: "default", Timeout: 2e9}
	fetch, err := NewFetcher(cfg)
	if err != nil {
		t.Fatal(err)
	}
	store := NewStore(fetch)
	if err := store.Load(); err != nil || store.Current().MaxItems != 50 {
		t.Fatalf("got %+v, %v", store.Current(), err)
	}

	cfg.Password = "wrong"
	fetch, _ = NewFetcher(cfg)
	if err := NewStore(fetch).Load(); err == nil {
		t.Fatal("expected wrong credentials to fail")
	}
}
