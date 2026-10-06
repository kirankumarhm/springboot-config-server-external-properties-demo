# node-service (version C - S3 (on Floci) backend)

A **Node.js** service with **one API**: it returns the `node.*` configuration it is using right
now. It is a client of the same Spring Cloud Config Server as the Spring Boot services - and it
refreshes from the **same broadcast**, with no polling.

**New to this?** Three terms are enough to follow this page:

- **Config Server** - a service that hands out configuration over HTTP. This version stores it in **S3 (on Floci)**.
- **Refresh** - picking up a changed value *while running*, with no restart.
- **Spring Cloud Bus** - a broadcast over RabbitMQ. When configuration changes, the Config Server sends one message, and every subscribed service re-reads its values.

## What it returns

```bash
# from: anywhere
curl -s http://localhost:8104/api/v1/node/config
```
```json
{"greeting":"Hello from Node.js","featureEnabled":true,"maxItems":25}
```

## How it works (no Spring here)

| Job | Done by |
|---|---|
| Read configuration from the Config Server (URL, login, merge order) | the [`cloud-config-client`](https://www.npmjs.com/package/cloud-config-client) library |
| Hear "configuration changed" from Spring Cloud Bus | `src/bus/bus-listener.js` on top of [`amqplib`](https://www.npmjs.com/package/amqplib), RabbitMQ's Node.js client |

No library exists for Spring Cloud Bus in Node.js, but it does not need one: the bus is plain
RabbitMQ. The Config Server publishes a small JSON message to the exchange `springCloudBus`:

```json
{"type":"RefreshRemoteApplicationEvent","destinationService":"node-service:**", ...}
```

The service listens on its own temporary queue, ignores messages for other services, and re-reads
its configuration when one is addressed to it (the same matching rule Spring uses).

1. **At startup** it loads and validates its configuration, and **refuses to start** if that fails.
2. **On a refresh** it re-reads; if that fails it **keeps the values it already has** and logs an error.
3. If RabbitMQ goes away it reconnects with back-off, and re-reads once on reconnecting (it may
   have missed a message).

## Folder layout

```
node-service/
├── src/
│   ├── index.js              <- entry point: load, join the bus, serve, shut down cleanly
│   ├── config/settings.js    <- reads and checks the environment variables below
│   ├── config/config-store.js<- loads from the Config Server, holds the current values
│   ├── config/node-config.js <- validates node.* (works with text or typed values)
│   ├── bus/                  <- Spring Cloud Bus listener and destination matching
│   ├── http/                 <- routes, security headers, problem details, OpenAPI document
│   └── logger.js             <- one JSON object per log line
├── test/                     <- node --test
├── package.json / package-lock.json
└── Dockerfile
```

## Run it

**1. With Docker Compose (everything at once)**

```bash
# from: version-c-s3/
./scripts/provision-floci.sh            # bucket, queue and seed files in Floci (idempotent)
docker compose -f docker/compose.yaml up -d --build
```
Floci must be running first (`floci start`).

**2. On your machine, without Docker** (Node.js 22+; Config Server and RabbitMQ running, e.g. from Compose)

```bash
# from: version-c-s3/node-service/
npm ci          # installs exactly the versions in package-lock.json
npm start
```
The defaults point at `localhost:8908` and RabbitMQ at `localhost:5674`; it listens on `8104`.

**3. On Kubernetes** - Floci EKS (`./k8s/deploy-floci-eks.sh`) or minikube (`./k8s/deploy-minikube.sh`), verified with `./k8s/verify-in-cluster.sh`.

## Change a value and watch it update

```bash
# from: anywhere
export AWS_ENDPOINT_URL=http://localhost.floci.io:4566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws s3 cp s3://acme-platform-config/main/node-service.yml /tmp/node-service.yml
sed -i.bak 's/max-items: 25/max-items: 30/' /tmp/node-service.yml
aws s3 cp /tmp/node-service.yml s3://acme-platform-config/main/node-service.yml
```
S3 sends an event to SQS; the Config Server consumes it and broadcasts the refresh.

```bash
# from: anywhere - within a second or two:
curl -s http://localhost:8104/api/v1/node/config
```

## Settings (environment variables)

| Variable | Default | What it is |
|---|---|---|
| `PORT` | `8104` | Port of the API |
| `CONFIG_SERVER_URL` | `http://localhost:8908` | Where the Config Server is (no credentials in the URL) |
| `CONFIG_CLIENT_USERNAME` / `CONFIG_CLIENT_PASSWORD` | `config-client` / `client-secret` | Login for the Config Server |
| `CONFIG_LABEL` / `CONFIG_PROFILE` | `main` / `default` | Which configuration to read |
| `CONFIG_TIMEOUT_MS` | `5000` | Give up on a Config Server call after this long |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5674` | The broker that carries Spring Cloud Bus |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Broker login |

## Endpoints

| Path | What |
|---|---|
| `GET /api/v1/node/config` | **The API** |
| `GET /v3/api-docs` | OpenAPI 3 document (paste into https://editor.swagger.io) |
| `GET /health` | Health check for Docker / Kubernetes |

Every response carries security headers; errors are `application/problem+json`.

## Test it

```bash
# from: version-c-s3/node-service/
npm ci && npm test
```

## Troubleshooting

| Log line | Why | Fix |
|---|---|---|
| `Startup failed ... Could not load configuration from http://...: connect ECONNREFUSED ...` | Config Server not running at that URL | Start it, or fix `CONFIG_SERVER_URL` |
| `... Invalid response: 401 - check CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD` | Wrong login | Fix the credentials |
| `Invalid node-service configuration: node.max-items must be ...` | A value breaks a rule | Fix the value in the backend |
| `Could not connect to RabbitMQ; will retry` (repeating) | Broker down or wrong host/port | Start RabbitMQ; check `RABBITMQ_HOST` / `RABBITMQ_PORT` |
| Behind a corporate proxy, `docker build` fails in `npm ci` with a certificate error | The proxy re-signs TLS | `docker build --build-arg EXTRA_CA_CERT="$(cat proxy-root.pem)" .` (see `Dockerfile`) |
