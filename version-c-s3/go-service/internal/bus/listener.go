package bus

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

// Exchange is the RabbitMQ topic exchange Spring Cloud Bus publishes to.
const Exchange = "springCloudBus"

const maxBackoff = 30 * time.Second

// event is the part of a Spring Cloud Bus message we need, for example:
//
//	{"type":"RefreshRemoteApplicationEvent","destinationService":"go-service:**",
//	 "originService":"config-server:8888:...","id":"...","timestamp":1791295618094}
type event struct {
	Type               string `json:"type"`
	DestinationService string `json:"destinationService"`
	OriginService      string `json:"originService"`
	ID                 string `json:"id"`
}

// Listener consumes Spring Cloud Bus events and calls onRefresh for the ones addressed to us.
type Listener struct {
	BusID     string
	amqpURL   string
	broker    string
	onRefresh func(reason string)
}

// NewListener creates a listener with a Spring-style bus id "<application>:<port>:<unique id>".
func NewListener(application string, port int, amqpURL, broker string, onRefresh func(string)) (*Listener, error) {
	random := make([]byte, 16)
	if _, err := rand.Read(random); err != nil {
		return nil, fmt.Errorf("generating the bus id: %w", err)
	}
	return &Listener{
		BusID:     fmt.Sprintf("%s:%d:%s", application, port, hex.EncodeToString(random)),
		amqpURL:   amqpURL,
		broker:    broker,
		onRefresh: onRefresh,
	}, nil
}

// Handle processes one raw message body and reports whether it triggered a refresh.
func (l *Listener) Handle(body []byte) bool {
	var e event
	if err := json.Unmarshal(body, &e); err != nil {
		slog.Warn("Ignoring a Spring Cloud Bus message that is not JSON")
		return false
	}
	if e.Type != "RefreshRemoteApplicationEvent" || e.OriginService == l.BusID ||
		!IsForThisInstance(e.DestinationService, l.BusID) {
		return false
	}
	slog.Info("Refresh requested over Spring Cloud Bus", "destination", e.DestinationService, "id", e.ID)
	l.onRefresh("bus event " + e.ID)
	return true
}

// Run consumes until ctx is cancelled, reconnecting with exponential backoff whenever the
// broker is unreachable or the connection drops. After a reconnect it refreshes once, because
// events published while disconnected are lost.
func (l *Listener) Run(ctx context.Context) {
	backoff := time.Second
	for attempt := 0; ; attempt++ {
		err := l.consume(ctx, attempt > 0)
		if ctx.Err() != nil {
			return
		}
		slog.Warn("Spring Cloud Bus connection lost; will retry", "error", err, "retryIn", backoff.String())
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, maxBackoff)
	}
}

func (l *Listener) consume(ctx context.Context, reconnect bool) error {
	config := amqp.Config{Properties: amqp.Table{"connection_name": l.BusID}, Heartbeat: 10 * time.Second}
	connection, err := amqp.DialConfig(l.amqpURL, config)
	if err != nil {
		return fmt.Errorf("connecting to RabbitMQ at %s: %w", l.broker, err)
	}
	defer connection.Close()
	channel, err := connection.Channel()
	if err != nil {
		return err
	}
	if err := channel.ExchangeDeclare(Exchange, "topic", true, false, false, false, nil); err != nil {
		return err
	}
	// Exclusive, auto-deleted queue: one per running instance, gone when the instance stops.
	queue, err := channel.QueueDeclare("", false, true, true, false, nil)
	if err != nil {
		return err
	}
	if err := channel.QueueBind(queue.Name, "#", Exchange, false, nil); err != nil {
		return err
	}
	deliveries, err := channel.Consume(queue.Name, l.BusID, true, true, false, false, nil)
	if err != nil {
		return err
	}
	slog.Info("Listening on Spring Cloud Bus", "busId", l.BusID, "broker", l.broker)
	if reconnect {
		l.onRefresh("reconnected to Spring Cloud Bus")
	}
	closed := connection.NotifyClose(make(chan *amqp.Error, 1))
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-closed:
			return fmt.Errorf("connection closed: %v", err)
		case delivery, ok := <-deliveries:
			if !ok {
				return fmt.Errorf("delivery channel closed")
			}
			l.Handle(delivery.Body)
		}
	}
}
