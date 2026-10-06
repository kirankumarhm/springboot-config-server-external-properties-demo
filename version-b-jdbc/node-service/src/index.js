// node-service entry point.
//
// 1. Load the configuration from the Config Server - and refuse to start without it.
// 2. Join Spring Cloud Bus, so a change in the configuration backend refreshes this service.
// 3. Serve GET /api/v1/node/config.
// 4. On SIGTERM (docker stop, Kubernetes rollout), stop accepting requests and exit cleanly.
import http from 'node:http';
import { BusListener } from './bus/bus-listener.js';
import { ConfigStore } from './config/config-store.js';
import { loadSettings } from './config/settings.js';
import { createRequestHandler } from './http/app.js';
import { logger } from './logger.js';

async function main() {
  const settings = loadSettings();
  const store = new ConfigStore(settings);
  await store.load();

  const bus = new BusListener(settings, (reason) => store.refresh(reason));
  await bus.start();

  const server = http.createServer(createRequestHandler(store));
  server.requestTimeout = 10_000;
  server.headersTimeout = 5_000;
  server.keepAliveTimeout = 5_000;
  server.listen(settings.port, () => logger.info('node-service listening', { port: settings.port }));

  const shutdown = (signal) => {
    logger.info('Shutting down', { signal });
    server.close(async () => {
      await bus.stop();
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 10_000).unref();
  };
  process.once('SIGTERM', shutdown);
  process.once('SIGINT', shutdown);
}

main().catch((error) => {
  logger.error('Startup failed', { error: error.message });
  process.exit(1);
});
