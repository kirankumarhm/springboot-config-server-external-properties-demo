# pricing-service (version C - S3 (on Floci) backend)

A Spring Boot service with **one API**: it returns the `pricing.*` configuration it is using
right now. Change a value in the configuration backend and this API returns the new value within
seconds - **without a restart**.

**New to this?** Three terms are enough to follow this page:

- **Config Server** - a service that hands out configuration over HTTP. This version stores it in **S3 (on Floci)**.
- **Refresh** - picking up a changed value *while running*, with no restart.
- **Spring Cloud Bus** - a broadcast over RabbitMQ. When configuration changes, the Config Server sends one message, and every subscribed service re-reads its values.

## What it returns

```bash
# from: anywhere
curl -s http://localhost:8102/api/v1/pricing/config
```
```json
{"currency":"INR","discountPercentage":10.0,"surgePricingEnabled":false,"surgeMultiplier":1.5}
```

Only this service's own properties - nothing else.

## How it gets its configuration

1. **At startup** it calls the Config Server (`spring.config.import: configserver:...`). If the
   server is unreachable it retries 6 times and then **refuses to start** - it never runs on
   missing configuration.
2. It checks the values against the rules in `config/PricingProperties.java`
   (for example `@Min`, `@Max`). An invalid value at startup **stops the service** with a message
   naming every bad property.
3. **When configuration changes**, the Config Server broadcasts over Spring Cloud Bus; Spring Cloud
   re-binds `PricingProperties` in place, and the API returns the new values. An
   invalid value arriving this way is logged as an `ERROR` (the service keeps running).

## Folder layout

```
pricing-service/
├── src/main/java/com/example/config/pricing/
│   ├── PricingServiceApplication.java   <- entry point
│   ├── config/        <- PricingProperties (the values), its validator, security, OpenAPI
│   ├── controller/    <- PricingController: GET /api/v1/pricing/config
│   ├── dto/           <- PricingConfigResponse: the JSON body
│   └── exception/     <- GlobalExceptionHandler: every error as RFC 9457 problem details
├── src/main/resources/application.yml   <- where the Config Server is, ports, RabbitMQ
├── config/            <- Checkstyle / SpotBugs rules used by the build
├── Dockerfile
└── pom.xml            <- builds on its own; shares nothing with other services
```

## Run it

Pick one. Every way needs the Config Server and RabbitMQ of version C running.

**1. With Docker Compose (everything at once)**

```bash
# from: version-c-s3/
mvn -B -Pfast package -DskipTests           # build the jars the images copy in
./scripts/provision-floci.sh            # bucket, queue and seed files in Floci (idempotent)
docker compose -f docker/compose.yaml up -d --build
```
Floci must be running first (`floci start`).

**2. On your machine, without Docker** (Config Server and RabbitMQ still running, e.g. from Compose)

```bash
# from: version-c-s3/pricing-service/
mvn spring-boot:run
```
The defaults in `application.yml` already point at `localhost:8908` (Config Server) and
`localhost:5674` (RabbitMQ), and the service listens on `8102` (health on `9102`).

**3. On Kubernetes** - Floci EKS (`./k8s/deploy-floci-eks.sh`) or minikube (`./k8s/deploy-minikube.sh`), verified with `./k8s/verify-in-cluster.sh`.

## Change a value and watch it update

```bash
# from: anywhere
export AWS_ENDPOINT_URL=http://localhost.floci.io:4566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws s3 cp s3://acme-platform-config/main/pricing-service.yml /tmp/pricing-service.yml
sed -i.bak 's/surge-pricing-enabled: false/surge-pricing-enabled: true/' /tmp/pricing-service.yml
aws s3 cp /tmp/pricing-service.yml s3://acme-platform-config/main/pricing-service.yml
```
S3 sends an event to SQS; the Config Server consumes it and broadcasts the refresh.

```bash
# from: anywhere - within a few seconds:
curl -s http://localhost:8102/api/v1/pricing/config
```

## Settings (environment variables)

| Variable | Default | What it is |
|---|---|---|
| `SERVER_PORT` | `8102` | Port of the API |
| `MANAGEMENT_PORT` | `9102` | Port of the health checks (`/actuator/health/liveness`, `/readiness`) |
| `CONFIG_SERVER_URI` | `http://localhost:8908` | Where the Config Server is |
| `CONFIG_CLIENT_USERNAME` / `CONFIG_CLIENT_PASSWORD` | `config-client` / `client-secret` | Login for the Config Server |
| `CONFIG_LABEL` | `main` | Which branch / label of the configuration to read |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5674` | The broker that carries Spring Cloud Bus |

## API documentation and security

- Swagger UI: http://localhost:8102/swagger-ui.html - raw OpenAPI: http://localhost:8102/v3/api-docs
- Every response carries security headers (CSP, HSTS, X-Frame-Options, ...). Only the API, the
  docs and the health checks are reachable; every other path answers `403`.
- Errors are `application/problem+json`, for example an unknown path:
  `{"type":"urn:problem:resource-not-found","title":"Resource not found","status":404,...}`.

## Test it

```bash
# from: version-c-s3/pricing-service/
mvn verify     # tests + every quality gate: formatting, Checkstyle, SpotBugs, coverage, ArchUnit
```

## Troubleshooting

| You see | Why | Fix |
|---|---|---|
| `Could not locate PropertySource and the fail fast property is set` | Config Server unreachable or the login is wrong | Start the Config Server; check `CONFIG_SERVER_URI` and `CONFIG_CLIENT_PASSWORD` |
| `Invalid pricing configuration: pricing.... must be ...` at startup | A value breaks a rule in `PricingProperties` | Fix the value in the backend, restart |
| `Refreshed pricing configuration is invalid` in the log | A refresh delivered a bad value | Fix the value in the backend; the next refresh clears it |
| A change never shows up | The Config Server did not broadcast (or RabbitMQ is down) | Check the Config Server log and that RabbitMQ is healthy |
