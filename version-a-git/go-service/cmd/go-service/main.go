// go-service entry point.
//
//  1. Load the configuration from the Config Server - and refuse to start without it.
//  2. Join Spring Cloud Bus, so a change in the configuration backend refreshes this service.
//  3. Serve GET /api/v1/go/config.
//  4. On SIGTERM (docker stop, Kubernetes rollout), stop accepting requests and exit cleanly.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"example.com/go-service/internal/bus"
	"example.com/go-service/internal/config"
	"example.com/go-service/internal/httpapi"
	"example.com/go-service/internal/settings"
)

func main() {
	// Structured JSON logs on stdout, which Docker and Kubernetes collect.
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("service", settings.Application))
	if err := run(); err != nil {
		slog.Error("go-service stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	s, err := settings.Load(os.Getenv)
	if err != nil {
		return err
	}
	fetch, err := config.NewFetcher(s.ConfigServer)
	if err != nil {
		return err
	}
	store := config.NewStore(fetch)
	if err := store.Load(); err != nil {
		return fmt.Errorf("startup failed, could not load configuration: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	broker := fmt.Sprintf("%s:%d", s.RabbitMQ.Host, s.RabbitMQ.Port)
	listener, err := bus.NewListener(settings.Application, s.Port, s.RabbitMQ.AMQPURL(), broker, store.Refresh)
	if err != nil {
		return err
	}
	go listener.Run(ctx)

	server := httpapi.NewServer(s.Port, httpapi.Handler(store.Current))
	serverErr := make(chan error, 1)
	go func() {
		slog.Info("go-service listening", "port", s.Port)
		serverErr <- server.ListenAndServe()
	}()

	select {
	case err := <-serverErr:
		return err
	case <-ctx.Done():
		slog.Info("Shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
}
