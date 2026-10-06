package bus

import (
	"regexp"
	"testing"
)

func TestDestinationMatching(t *testing.T) {
	busID := "go-service:8085:abc123"
	for destination, want := range map[string]bool{
		"":                  true,
		"**":                true,
		"go-service:**":     true,
		"go-service":        true,
		"go-service:8085:*": true,
		"go-service:9999:*": false,
		"go:**":             false,
		"node-service:**":   false,
	} {
		if got := IsForThisInstance(destination, busID); got != want {
			t.Errorf("IsForThisInstance(%q) = %v, want %v", destination, got, want)
		}
	}
}

func TestHandleRefreshesOnlyForEventsAddressedToUs(t *testing.T) {
	var reasons []string
	l, err := NewListener("go-service", 8085, "amqp://unused", "unused", func(r string) { reasons = append(reasons, r) })
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^go-service:8085:[0-9a-f]{32}$`).MatchString(l.BusID) {
		t.Fatalf("bus id %q does not follow <application>:<port>:<unique id>", l.BusID)
	}

	l.Handle([]byte(`{"type":"RefreshRemoteApplicationEvent","destinationService":"go-service:**","originService":"config-server:8888:x","id":"e1"}`))
	l.Handle([]byte(`{"type":"RefreshRemoteApplicationEvent","destinationService":"node-service:**","id":"e2"}`))
	l.Handle([]byte(`{"type":"AckRemoteApplicationEvent","destinationService":"**","id":"e3"}`))
	l.Handle([]byte(`{"type":"RefreshRemoteApplicationEvent","destinationService":"**","originService":"` + l.BusID + `","id":"e4"}`))
	l.Handle([]byte(`not json`))

	if len(reasons) != 1 || reasons[0] != "bus event e1" {
		t.Fatalf("got %v", reasons)
	}
}
