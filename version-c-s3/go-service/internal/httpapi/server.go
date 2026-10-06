package httpapi

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"example.com/go-service/internal/config"
)

var securityHeaders = map[string]string{
	"Content-Security-Policy":   "default-src 'none'; frame-ancestors 'none'",
	"X-Content-Type-Options":    "nosniff",
	"X-Frame-Options":           "DENY",
	"Referrer-Policy":           "strict-origin-when-cross-origin",
	"Strict-Transport-Security": "max-age=31536000; includeSubDomains",
	"Cache-Control":             "no-store",
}

// Handler routes:
//
//	GET /api/v1/go/config - the API: the go.* configuration in use
//	GET /v3/api-docs      - the OpenAPI 3 document
//	GET /health           - liveness/readiness probe for Docker and Kubernetes
//
// Every response carries security headers; every error is application/problem+json.
func Handler(current func() config.GoConfig) http.Handler {
	routes := map[string]func(http.ResponseWriter){
		"/api/v1/go/config": func(w http.ResponseWriter) { writeJSON(w, current()) },
		"/v3/api-docs": func(w http.ResponseWriter) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(openAPIDocument))
		},
		"/health": func(w http.ResponseWriter) { writeJSON(w, map[string]string{"status": "UP"}) },
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for name, value := range securityHeaders {
			w.Header().Set(name, value)
		}
		defer func() {
			if recovered := recover(); recovered != nil {
				errorID := newErrorID()
				slog.Error("Unexpected error", "errorId", errorID, "error", fmt.Sprint(recovered))
				writeProblem(w, http.StatusInternalServerError, "Internal server error", "An unexpected error occurred.", errorID)
			}
		}()
		route, ok := routes[r.URL.Path]
		switch {
		case !ok:
			writeProblem(w, http.StatusNotFound, "Resource not found", "No endpoint "+r.URL.Path, "")
		case r.Method != http.MethodGet && r.Method != http.MethodHead:
			w.Header().Set("Allow", "GET, HEAD")
			writeProblem(w, http.StatusMethodNotAllowed, "Method not allowed", "Request method '"+r.Method+"' is not supported", "")
		default:
			route(w)
		}
	})
}

// NewServer wraps the handler with timeouts, so a slow client cannot hold connections open.
func NewServer(port int, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              fmt.Sprintf(":%d", port),
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Warn("Writing the response failed", "error", err)
	}
}

func newErrorID() string {
	random := make([]byte, 16)
	_, _ = rand.Read(random)
	return hex.EncodeToString(random)
}
