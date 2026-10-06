# inventory-service (version A - Git backend)

A Spring Boot service with **one API**: it returns the `inventory.*` configuration it is using
right now. Change a value in the configuration backend and this API returns the new value within
seconds - **without a restart**.

**New to this?** Three terms are enough to follow this page:

- **Config Server** - a service that hands out configuration over HTTP. This version stores it in **Git**.
- **Refresh** - picking up a changed value *while running*, with no restart.
- **Spring Cloud Bus** - a broadcast over RabbitMQ. When configuration changes, the Config Server sends one message, and every subscribed service re-reads its values.

## What it returns

```bash
# from: anywhere
curl -s http://localhost:8081/api/v1/inventory/config
```
```json
{"warehouseCode":"WH-BLR-01","maxOrderQuantity":100,"expressShippingEnabled":true,"lowStockThreshold":115}
```

Only this service's own properties - nothing else.

## How it gets its configuration

1. **At startup** it calls the Config Server (`spring.config.import: configserver:...`). If the
   server is unreachable it retries 6 times and then **refuses to start** - it never runs on
   missing configuration.
2. It checks the values against the rules in `config/InventoryProperties.java`
   (for example `@Min`, `@Max`). An invalid value at startup **stops the service** with a message
   naming every bad property.
3. **When configuration changes**, the Config Server broadcasts over Spring Cloud Bus; Spring Cloud
   re-binds `InventoryProperties` in place, and the API returns the new values. An
   invalid value arriving this way is logged as an `ERROR` (the service keeps running).

## Folder layout

```
inventory-service/
├── src/main/java/com/example/config/inventory/
│   ├── InventoryServiceApplication.java   <- entry point
│   ├── config/        <- InventoryProperties (the values), its validator, security, OpenAPI
│   ├── controller/    <- InventoryController: GET /api/v1/inventory/config
│   ├── dto/           <- InventoryConfigResponse: the JSON body
│   └── exception/     <- GlobalExceptionHandler: every error as RFC 9457 problem details
├── src/main/resources/application.yml   <- where the Config Server is, ports, RabbitMQ
├── config/            <- Checkstyle / SpotBugs rules used by the build
├── Dockerfile
└── pom.xml            <- builds on its own; shares nothing with other services
```

## Run it

Pick one. Every way needs the Config Server and RabbitMQ of version A running.

**1. With Docker Compose (everything at once)**

```bash
# from: version-a-git/
mvn -B -Pfast package -DskipTests           # build the jars the images copy in
CONFIG_REPO_URI=file:///config-repo CONFIG_REPO_SEARCH_PATHS= CONFIG_REPO_FORCE_PULL=false \
  docker compose -f docker/compose.yaml up -d --build
```
The three variables make the Config Server read the **local** `config-repo/` folder instead of the GitHub repository, so a local commit is enough to change a value. See [../README.md](../README.md) for the GitHub mode.

**2. On your machine, without Docker** (Config Server and RabbitMQ still running, e.g. from Compose)

```bash
# from: version-a-git/inventory-service/
mvn spring-boot:run
```
The defaults in `application.yml` already point at `localhost:8888` (Config Server) and
`localhost:5672` (RabbitMQ), and the service listens on `8081` (health on `9081`).

**3. On Kubernetes** - minikube (`./k8s/deploy-minikube.sh`). In a cluster the Config Server reads the **GitHub** repository, so a change must be committed **and pushed** from the repository root, then broadcast - see section 13 of [../README.md](../README.md).

## Change a value and watch it update

```bash
# from: version-a-git/config-repo/      (its own small Git repository)
sed -i.bak 's/max-order-quantity: 100/max-order-quantity: 750/' inventory-service.yml && rm inventory-service.yml.bak
git commit -am "Change max-order-quantity"            # the post-commit hook tells the Config Server
```

```bash
# from: anywhere - within a few seconds:
curl -s http://localhost:8081/api/v1/inventory/config
```

## Settings (environment variables)

| Variable | Default | What it is |
|---|---|---|
| `SERVER_PORT` | `8081` | Port of the API |
| `MANAGEMENT_PORT` | `9081` | Port of the health checks (`/actuator/health/liveness`, `/readiness`) |
| `CONFIG_SERVER_URI` | `http://localhost:8888` | Where the Config Server is |
| `CONFIG_CLIENT_USERNAME` / `CONFIG_CLIENT_PASSWORD` | `config-client` / `client-secret` | Login for the Config Server |
| `CONFIG_LABEL` | `main` | Which branch / label of the configuration to read |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | The broker that carries Spring Cloud Bus |

## API documentation and security

- Swagger UI: http://localhost:8081/swagger-ui.html - raw OpenAPI: http://localhost:8081/v3/api-docs
- Every response carries security headers (CSP, HSTS, X-Frame-Options, ...). Only the API, the
  docs and the health checks are reachable; every other path answers `403`.
- Errors are `application/problem+json`, for example an unknown path:
  `{"type":"urn:problem:resource-not-found","title":"Resource not found","status":404,...}`.

## Test it

```bash
# from: version-a-git/inventory-service/
mvn verify     # tests + every quality gate: formatting, Checkstyle, SpotBugs, coverage, ArchUnit
```

## Troubleshooting

| You see | Why | Fix |
|---|---|---|
| `Could not locate PropertySource and the fail fast property is set` | Config Server unreachable or the login is wrong | Start the Config Server; check `CONFIG_SERVER_URI` and `CONFIG_CLIENT_PASSWORD` |
| `Invalid inventory configuration: inventory.... must be ...` at startup | A value breaks a rule in `InventoryProperties` | Fix the value in the backend, restart |
| `Refreshed inventory configuration is invalid` in the log | A refresh delivered a bad value | Fix the value in the backend; the next refresh clears it |
| A change never shows up | The Config Server did not broadcast (or RabbitMQ is down) | Check the Config Server log and that RabbitMQ is healthy |
