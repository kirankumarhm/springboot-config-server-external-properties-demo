package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"example.com/go-service/internal/config"
)

var current = func() config.GoConfig { return config.GoConfig{Greeting: "Hi", FeatureEnabled: true, MaxItems: 50} }

func TestConfigEndpointReturnsOnlyTheGoConfigWithSecurityHeaders(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/go/config", nil))

	if w.Code != http.StatusOK || strings.TrimSpace(w.Body.String()) != `{"greeting":"Hi","featureEnabled":true,"maxItems":50}` {
		t.Fatalf("got %d %s", w.Code, w.Body)
	}
	for _, header := range []string{"X-Frame-Options", "X-Content-Type-Options", "Content-Security-Policy"} {
		if w.Header().Get(header) == "" {
			t.Errorf("missing %s", header)
		}
	}
}

func TestOpenAPIDocumentIsValidJSONAndHealthIsUp(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/v3/api-docs", nil))
	var doc map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &doc); err != nil || doc["openapi"] != "3.0.3" {
		t.Fatalf("bad OpenAPI document: %v", err)
	}
	w = httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/health", nil))
	if strings.TrimSpace(w.Body.String()) != `{"status":"UP"}` {
		t.Fatalf("got %s", w.Body)
	}
}

func TestErrorsAreProblemDetails(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/nope", nil))
	if w.Code != http.StatusNotFound || w.Header().Get("Content-Type") != "application/problem+json" {
		t.Fatalf("got %d %s", w.Code, w.Header().Get("Content-Type"))
	}

	w = httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/v1/go/config", nil))
	if w.Code != http.StatusMethodNotAllowed || w.Header().Get("Allow") != "GET, HEAD" {
		t.Fatalf("got %d allow=%q", w.Code, w.Header().Get("Allow"))
	}
}

func TestHeadIsAnsweredLikeGet(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(current).ServeHTTP(w, httptest.NewRequest(http.MethodHead, "/api/v1/go/config", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d", w.Code)
	}
}

func TestAPanicBecomesA500WithoutInternals(t *testing.T) {
	broken := func() config.GoConfig { panic("secret detail") }
	w := httptest.NewRecorder()
	Handler(broken).ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/go/config", nil))
	var body problem
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if w.Code != http.StatusInternalServerError || body.ErrorID == "" || strings.Contains(w.Body.String(), "secret") {
		t.Fatalf("got %d %s", w.Code, w.Body)
	}
}
