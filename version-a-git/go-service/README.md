# go-service (version A - Git backend)

A **Go** service with **one API**: it returns the `go.*` configuration it is using right
now. It is a client of the same Spring Cloud Config Server as the Spring Boot services - and it
refreshes from the **same broadcast**, with no polling.

**New to this?** Three terms are enough to follow this page:

- **Config Server** - a service that hands out configuration over HTTP. This version stores it in **Git**.
- **Refresh** - picking up a changed value *while running*, with no restart.
- **Spring Cloud Bus** - a broadcast over RabbitMQ. When configuration changes, the Config Server sends one message, and every subscribed service re-reads its values.

## What it returns

```bash
# from: anywhere
curl -s http://localhost:8085/api/v1/go/config
```
```json
{"greeting":"Hello from Go","featureEnabled":false,"maxItems":50}
```

## How it works (no Spring here)

| Job | Done by |
|---|---|
| Read configuration from the Config Server (URL, login, merge order) | the [`cloudconfigclient`](https://github.com/Piszmog/cloudconfigclient) library (fetching only - see note below) |
| Hear "configuration changed" from Spring Cloud Bus | `internal/bus` on top of [`amqp091-go`](https://github.com/rabbitmq/amqp091-go), RabbitMQ's official Go client |

No library exists for Spring Cloud Bus in Go, but it does not need one: the bus is plain
RabbitMQ. The Config Server publishes a small JSON message to the exchange `springCloudBus`:

```json
{"type":"RefreshRemoteApplicationEvent","destinationService":"go-service:**", ...}
```

The service listens on its own temporary queue, ignores messages for other services, and re-reads
its configuration when one is addressed to it (the same matching rule Spring uses).

1. **At startup** it loads and validates its configuration, and **refuses to start** if that fails.
2. **On a refresh** it re-reads; if that fails it **keeps the values it already has** and logs an error.
3. If RabbitMQ goes away it reconnects with back-off, and re-reads once on reconnecting (it may
   have missed a message).

## Folder layout

```
go-service/
├── cmd/go-service/main.go    <- entry point: load, join the bus, serve, shut down cleanly
├── internal/
│   ├── settings/             <- reads and checks the environment variables below
│   ├── config/               <- fetches with cloudconfigclient, merges, validates go.*, holds the values
│   ├── bus/                  <- Spring Cloud Bus listener and destination matching
│   └── httpapi/              <- routes, security headers, problem details, OpenAPI document, timeouts
├── go.mod / go.sum           <- dependencies, pinned
└── Dockerfile                <- builds a static binary, runs it as a non-root user
```

> **Why the merge is done here, not by the library:** `cloudconfigclient`'s `Unmarshal` applies
> the property sources in the order the server lists them, so the *least* specific one wins - the
> shared `application.yml` would override `go-service.yml`. It also fails on the JDBC backend,
> which stores `true` as the text `"true"`. `internal/config` merges in Spring's order and converts
> text values, and each rule has a test.

## Run it

**1. With Docker Compose (everything at once)**

```bash
# from: version-a-git/
CONFIG_REPO_URI=file:///config-repo CONFIG_REPO_SEARCH_PATHS= CONFIG_REPO_FORCE_PULL=false \
  docker compose -f docker/compose.yaml up -d --build
```
The three variables make the Config Server read the **local** `config-repo/` folder instead of the GitHub repository, so a local commit is enough to change a value. See [../README.md](../README.md) for the GitHub mode.

**2. On your machine, without Docker** (Go 1.23+; Config Server and RabbitMQ running, e.g. from Compose)

```bash
# from: version-a-git/go-service/
go run ./cmd/go-service
```
The defaults point at `localhost:8888` and RabbitMQ at `localhost:5672`; it listens on `8085`.

**3. On Kubernetes** - minikube (`./k8s/deploy-minikube.sh`). In a cluster the Config Server reads the **GitHub** repository, so a change must be committed **and pushed** from the repository root, then broadcast - see section 13 of [../README.md](../README.md).

## Change a value and watch it update

```bash
# from: version-a-git/config-repo/      (its own small Git repository)
sed -i.bak 's/max-items: 50/max-items: 55/' go-service.yml && rm go-service.yml.bak
git commit -am "Change max-items"            # the post-commit hook tells the Config Server
```

```bash
# from: anywhere - within a second or two:
curl -s http://localhost:8085/api/v1/go/config
```

## Settings (environment variables)

| Variable | Default | What it is |
|---|---|---|
| `PORT` | `8085` | Port of the API |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Where the Config Server is (no credentials in the URL) |
| `CONFIG_CLIENT_USERNAME` / `CONFIG_CLIENT_PASSWORD` | `config-client` / `client-secret` | Login for the Config Server |
| `CONFIG_LABEL` / `CONFIG_PROFILE` | `main` / `default` | Which configuration to read |
| `CONFIG_TIMEOUT_MS` | `5000` | Give up on a Config Server call after this long |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | The broker that carries Spring Cloud Bus |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Broker login |

## Endpoints

| Path | What |
|---|---|
| `GET /api/v1/go/config` | **The API** |
| `GET /v3/api-docs` | OpenAPI 3 document (paste into https://editor.swagger.io) |
| `GET /health` | Health check for Docker / Kubernetes |

Every response carries security headers; errors are `application/problem+json`.

## Test it

```bash
# from: version-a-git/go-service/
gofmt -l . && go vet ./... && go test -race ./...
```

## Troubleshooting

| Log line | Why | Fix |
|---|---|---|
| `startup failed, could not load configuration: fetching go-service/default/main from http://...: ... connect: connection refused` | Config Server not running at that URL | Start it, or fix `CONFIG_SERVER_URL` |
| `... server responded with status code '401'` | Wrong login | Fix the credentials |
| `invalid go-service configuration: go.max-items must be ...` | A value breaks a rule | Fix the value in the backend |
| `Spring Cloud Bus connection lost; will retry` (repeating) | Broker down or wrong host/port | Start RabbitMQ; check `RABBITMQ_HOST` / `RABBITMQ_PORT` |
| Behind a corporate proxy, `docker build` fails in `go mod download` with `x509: certificate signed by unknown authority` | The proxy re-signs TLS | `docker build --build-arg EXTRA_CA_CERT="$(cat proxy-root.pem)" .` (see `Dockerfile`) |
