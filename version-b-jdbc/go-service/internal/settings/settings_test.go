package settings

import (
	"strings"
	"testing"
)

func env(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}

func TestDefaultsSuitALocalRun(t *testing.T) {
	s, err := Load(env(nil))
	if err != nil {
		t.Fatal(err)
	}
	if s.Port != 8095 || s.ConfigServer.URL != "http://localhost:8898" || s.ConfigServer.Label != "main" {
		t.Fatalf("unexpected defaults: %+v", s)
	}
}

func TestRejectsBadValues(t *testing.T) {
	for name, values := range map[string]map[string]string{
		"port":        {"PORT": "70000"},
		"timeout":     {"CONFIG_TIMEOUT_MS": "abc"},
		"url":         {"CONFIG_SERVER_URL": "ftp://x"},
		"credentials": {"CONFIG_SERVER_URL": "http://user:pass@x"},
	} {
		if _, err := Load(env(values)); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestAMQPURLEscapesCredentials(t *testing.T) {
	r := RabbitMQ{Host: "rabbitmq", Port: 5672, Username: "guest", Password: "p@ss/word"}
	if got := r.AMQPURL(); !strings.Contains(got, "p%40ss%2Fword") || !strings.HasPrefix(got, "amqp://guest:") {
		t.Fatalf("got %s", got)
	}
}
