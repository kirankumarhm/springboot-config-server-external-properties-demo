// Package httpapi is go-service's HTTP layer.
package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// problem is an RFC 9457 "problem details" body (application/problem+json), the same format the
// Spring Boot services return.
type problem struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail"`
	Timestamp string `json:"timestamp"`
	ErrorID   string `json:"errorId,omitempty"`
}

func writeProblem(w http.ResponseWriter, status int, title, detail, errorID string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	body := problem{
		Type:      "urn:problem:" + strings.ReplaceAll(strings.ToLower(title), " ", "-"),
		Title:     title,
		Status:    status,
		Detail:    detail,
		Timestamp: time.Now().UTC().Format(time.RFC3339Nano),
		ErrorID:   errorID,
	}
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Warn("Writing the error response failed", "error", err)
	}
}
