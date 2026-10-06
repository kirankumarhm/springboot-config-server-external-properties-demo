// Joins Spring Cloud Bus, so this Node.js service is refreshed by the SAME broadcast that
// refreshes the Spring Boot services - no polling.
//
// Spring Cloud Bus is plain RabbitMQ: the Config Server publishes JSON events to the topic
// exchange "springCloudBus". Every subscriber binds its own temporary queue to that exchange,
// receives every event, and acts only on the ones addressed to it. A refresh event looks like:
//   {"type":"RefreshRemoteApplicationEvent","destinationService":"node-service:**",
//    "originService":"config-server:8888:...","id":"...","timestamp":1791295618094}
import { randomUUID } from 'node:crypto';
import amqp from 'amqplib';
import { logger } from '../logger.js';
import { isForThisInstance } from './destination.js';

const EXCHANGE = 'springCloudBus';
const MAX_BACKOFF_MS = 30_000;

export class BusListener {
  #settings;
  #onRefresh;
  #connect;
  #connection;
  #stopped = false;
  #retryTimer;
  #attempt = 0;
  busId;

  constructor(settings, onRefresh, connect = amqp.connect) {
    this.#settings = settings;
    this.#onRefresh = onRefresh;
    this.#connect = connect;
    this.busId = `${settings.application}:${settings.port}:${randomUUID().replaceAll('-', '')}`;
  }

  // Handles one raw message body. Exposed for tests.
  handle(body) {
    let event;
    try {
      event = JSON.parse(body);
    } catch {
      logger.warn('Ignoring a Spring Cloud Bus message that is not JSON');
      return false;
    }
    if (event.type !== 'RefreshRemoteApplicationEvent' || event.originService === this.busId) {
      return false;
    }
    if (!isForThisInstance(event.destinationService, this.busId)) {
      return false;
    }
    logger.info('Refresh requested over Spring Cloud Bus', { destination: event.destinationService, id: event.id });
    this.#onRefresh(`bus event ${event.id}`);
    return true;
  }

  async start() {
    const { host, port, username, password } = this.#settings.rabbitmq;
    try {
      this.#connection = await this.#connect({
        protocol: 'amqp', hostname: host, port, username, password,
        clientProperties: { connection_name: this.busId },
      });
      this.#connection.on('error', (error) => logger.warn('RabbitMQ connection error', { error: error.message }));
      this.#connection.on('close', () => this.#scheduleReconnect());
      const channel = await this.#connection.createChannel();
      await channel.assertExchange(EXCHANGE, 'topic', { durable: true });
      // Exclusive, auto-deleted queue: one per running instance, gone when the instance stops.
      const { queue } = await channel.assertQueue('', { exclusive: true, autoDelete: true });
      await channel.bindQueue(queue, EXCHANGE, '#');
      await channel.consume(queue, (message) => message && this.handle(message.content.toString()), { noAck: true });
      logger.info('Listening on Spring Cloud Bus', { busId: this.busId, broker: `${host}:${port}` });
      if (this.#attempt > 0) {
        // Events sent while we were disconnected are lost, so catch up once.
        this.#onRefresh('reconnected to Spring Cloud Bus');
      }
      this.#attempt = 0;
    } catch (error) {
      logger.warn('Could not connect to RabbitMQ; will retry', { error: error.message });
      this.#scheduleReconnect();
    }
  }

  #scheduleReconnect() {
    if (this.#stopped || this.#retryTimer) {
      return;
    }
    this.#attempt += 1;
    const delay = Math.min(1000 * 2 ** (this.#attempt - 1), MAX_BACKOFF_MS);
    this.#retryTimer = setTimeout(() => {
      this.#retryTimer = undefined;
      this.start();
    }, delay);
  }

  async stop() {
    this.#stopped = true;
    clearTimeout(this.#retryTimer);
    await this.#connection?.close().catch(() => {});
  }
}
