# lambda-service (version C - S3 (on Floci) backend)

An **AWS Lambda** function (Node.js 22) behind an **API Gateway** HTTP route, running in
[Floci](https://floci.io), the local AWS emulator. It has one API, `GET /api/v1/lambda/config`,
which returns the `lambda.*` configuration from the Spring Cloud Config Server.

**New to this?** Three terms are enough to follow this page:

- **Config Server** - a service that hands out configuration over HTTP. This version stores it in **S3 (on Floci)**.
- **Refresh** - picking up a changed value *while running*, with no restart.
- **Spring Cloud Bus** - a broadcast over RabbitMQ. When configuration changes, the Config Server sends one message, and every subscribed service re-reads its values.

- **Lambda** - a function AWS runs on demand. Between calls it is **frozen**.

## Why it reads the configuration on every call

A frozen function cannot keep a RabbitMQ connection open, and a timer does not tick while it is
frozen - so it cannot join Spring Cloud Bus like the other services. Instead it calls the Config
Server on **every invocation**. A change is therefore visible on the very next call, with no
broadcast needed. (It uses the same [`cloud-config-client`](https://www.npmjs.com/package/cloud-config-client)
library as node-service.)

## What it returns

```bash
# from: version-c-s3/lambda-service/
./scripts/invoke-floci.sh
```
```json
{"greeting":"Hello from AWS Lambda","featureEnabled":true,"maxItems":10}
```

## Folder layout

```
lambda-service/
├── src/
│   ├── handler.js             <- the Lambda handler: routing, security headers, problem details
│   ├── config/settings.js     <- reads and checks the function's environment variables
│   ├── config/config-loader.js<- loads from the Config Server with cloud-config-client
│   ├── config/lambda-config.js<- validates lambda.*
│   └── logger.js
├── openapi.json               <- OpenAPI 3 document of the API Gateway route
├── scripts/deploy-floci.sh    <- IAM role -> function -> API Gateway route (safe to re-run)
├── scripts/invoke-floci.sh    <- calls the route and prints the JSON
├── test/                      <- node --test
└── package.json / package-lock.json
```

## Deploy and call it

Needs: Floci running (`floci start`), this version's Config Server running (see [../README.md](../README.md)),
the AWS CLI, Node.js 22 and `zip`.

```bash
# from: version-c-s3/lambda-service/
./scripts/deploy-floci.sh     # creates or updates role, function config-s3-lambda-service and its API
./scripts/invoke-floci.sh     # prints the JSON
```

What `deploy-floci.sh` does, step by step:

1. **Packages** `src/` with production dependencies only (`npm ci --omit=dev`) into `function.zip`.
2. **Creates an IAM role** the function runs as - it may only write its own logs (least privilege).
3. **Creates or updates the function** (`nodejs22.x`, handler `src/handler.handler`) and sets
   `CONFIG_SERVER_URL=http://host.docker.internal:8908`: the function runs in a container
   Floci starts, so it reaches the Config Server through the host.
4. **Creates an API Gateway HTTP API** with the route `GET /api/v1/lambda/config` and prints its URL:
   `http://localhost:4566/_aws/execute-api/<api-id>/$default/api/v1/lambda/config`.

Run without Floci (just the handler, on your machine):

```bash
# from: version-c-s3/lambda-service/
npm ci
CONFIG_SERVER_URL=http://localhost:8908 node -e "import('./src/handler.js').then(async m => console.log(await m.handler({})))"
```

## Change a value and watch it update

```bash
# from: anywhere
export AWS_ENDPOINT_URL=http://localhost.floci.io:4566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws s3 cp s3://acme-platform-config/main/lambda-service.yml /tmp/lambda-service.yml
sed -i.bak 's/max-items: 10/max-items: 12/' /tmp/lambda-service.yml
aws s3 cp /tmp/lambda-service.yml s3://acme-platform-config/main/lambda-service.yml
```
S3 sends an event to SQS; the Config Server consumes it and broadcasts the refresh.

```bash
# from: version-c-s3/lambda-service/ - the very next call shows it:
./scripts/invoke-floci.sh
```

## Settings (the function's environment variables)

| Variable | Set by deploy-floci.sh to | What it is |
|---|---|---|
| `CONFIG_SERVER_URL` | `http://host.docker.internal:8908` | Where the Config Server is |
| `CONFIG_CLIENT_USERNAME` / `CONFIG_CLIENT_PASSWORD` | `config-client` / `client-secret` | Login for the Config Server |
| `CONFIG_LABEL` / `CONFIG_PROFILE` | (default `main` / `default`) | Which configuration to read |
| `CONFIG_TIMEOUT_MS` | (default `5000`) | Give up on a Config Server call after this long |

## Responses

| Status | When |
|---|---|
| `200` | The configuration |
| `404` / `405` | Unknown path / not a GET - `application/problem+json` |
| `503` | The Config Server could not be reached or returned bad values. The body has an `errorId`; the reason is in the function's log under the same id - never in the response. |

## Test it

```bash
# from: version-c-s3/lambda-service/
npm ci && npm test
```

## Troubleshooting

| You see | Why | Fix |
|---|---|---|
| `Floci is not reachable at http://localhost:4566` | Floci is not running | `floci start` |
| `invoke-floci.sh` prints a `503` problem | The function cannot reach the Config Server | Start this version's stack; the server must be published on the host port 8908 |
| `API ... not found - run ./scripts/deploy-floci.sh first` | Not deployed yet | Run the deploy script |
