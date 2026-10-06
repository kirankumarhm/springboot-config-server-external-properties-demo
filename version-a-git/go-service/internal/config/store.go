package config

import (
	"fmt"
	"log/slog"
	"net/http"
	"sync"

	"example.com/go-service/internal/settings"
	"github.com/Piszmog/cloudconfigclient/v2"
)

// Fetcher returns the property sources for one application/profile/label, most specific first,
// exactly as the Config Server lists them.
type Fetcher func() ([]cloudconfigclient.PropertySource, error)

// NewFetcher fetches with the cloudconfigclient library, which builds the URL
// ({url}/{application}/{profile}/{label}) and sends Basic auth.
func NewFetcher(s settings.ConfigServer) (Fetcher, error) {
	httpClient := &http.Client{Timeout: s.Timeout}
	client, err := cloudconfigclient.New(cloudconfigclient.Basic(httpClient, s.Username, s.Password, s.URL))
	if err != nil {
		return nil, fmt.Errorf("creating the Config Server client: %w", err)
	}
	return func() ([]cloudconfigclient.PropertySource, error) {
		source, err := client.GetConfigurationWithLabel(s.Label, settings.Application, s.Profile)
		if err != nil {
			return nil, fmt.Errorf("fetching %s/%s/%s from %s: %w", settings.Application, s.Profile, s.Label, s.URL, err)
		}
		return source.PropertySources, nil
	}, nil
}

// merge applies the sources so that the FIRST (most specific) one wins, as Spring does.
//
// We merge here instead of using the library's Source.Unmarshal, which lets the LAST source win
// and would let the shared application.yml override go-service.yml.
func merge(sources []cloudconfigclient.PropertySource) map[string]any {
	merged := map[string]any{}
	for i := len(sources) - 1; i >= 0; i-- {
		for key, value := range sources[i].Source {
			merged[key] = value
		}
	}
	return merged
}

// Store holds the configuration currently in use. It is safe for concurrent use.
type Store struct {
	fetch   Fetcher
	mu      sync.RWMutex
	current GoConfig
}

// NewStore returns an empty store; call Load before serving requests.
func NewStore(fetch Fetcher) *Store {
	return &Store{fetch: fetch}
}

// Current returns the configuration in use.
func (s *Store) Current() GoConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.current
}

func (s *Store) load() (GoConfig, error) {
	sources, err := s.fetch()
	if err != nil {
		return GoConfig{}, err
	}
	return toGoConfig(merge(sources))
}

// Load is used at startup: there is nothing to fall back on, so any failure is returned and the
// service must not start.
func (s *Store) Load() error {
	latest, err := s.load()
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.current = latest
	s.mu.Unlock()
	slog.Info("Configuration loaded", "config", latest)
	return nil
}

// Refresh is used after startup: a failure keeps the values already in use and is logged.
func (s *Store) Refresh(reason string) {
	latest, err := s.load()
	if err != nil {
		slog.Error("Configuration refresh failed; keeping the values in use", "reason", reason, "error", err)
		return
	}
	s.mu.Lock()
	changed := latest != s.current
	s.current = latest
	s.mu.Unlock()
	if changed {
		slog.Info("Configuration changed", "reason", reason, "config", latest)
	} else {
		slog.Info("Configuration unchanged", "reason", reason)
	}
}
