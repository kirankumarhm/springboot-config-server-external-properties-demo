# Technical Specification — Dynamic External Configuration with Spring Cloud Config

Companion to [REQUIREMENTS.md](REQUIREMENTS.md). Requirement IDs (`FR-xx`, `NFR-xx`, `CON-xx`,
`AC-xx`) referenced here are defined there. Full mapping in §14.

| Field | Value |
|---|---|
| Spec version | 1.2 |
| Date | 2026-08-30 |
| Status | **Implemented in [version-a-git/](version-a-git/) — 29/29 acceptance checks passing.** Several statements below were corrected by what the running system actually did; see [README.md](README.md#findings-that-contradict-the-original-specification) for the full list. Corrections are marked **[CORRECTED]** inline. |
| Scope of this document | **Version A — Git backend.** Versions B (PostgreSQL/JDBC) and C (AWS S3) are specified in [SPECIFICATION-BACKENDS.md](SPECIFICATION-BACKENDS.md). Everything in §6 (clients), §7 (patterns), §8 (cross-cutting) and §10 (testing) applies unchanged to all three. |

---

## 1. Verified version matrix

All versions below were checked against Maven Central metadata and spring.io on 2026-08-30.

| Component | Version | Note |
|---|---|---|
| Spring Boot | **4.0.8** | Latest 4.0.x. Latest GA overall is 4.1.1; see §13 for the upgrade path. |
| Spring Cloud BOM | **2025.1.3** | Codename **Oakwood**. Current GA train; targets Boot 4.0.x and 4.1.x (4.1.x from 2025.1.2). |
| `spring-cloud-config` | 5.0.5 | Managed by the BOM. |
| `spring-cloud-bus` | 5.0.3 | Managed by the BOM. Built on Spring Cloud Stream 5.0.3. |
| `spring-cloud-commons` | 5.0.3 | Provides `RefreshScope`, `ContextRefresher`, `ConfigurationPropertiesRebinder`. |
| Java | 21 (LTS) | Boot 4 baseline is 17; 21 matches the installed JDK. |
| Maven | 3.9.15 | Multi-module reactor. |
| RabbitMQ | 4.x (Docker `rabbitmq:4-management`) | Bus transport. Shared by all three backend versions. |

Backend-specific additions (PostgreSQL, Flyway, AWS SDK, Spring Cloud AWS 4.1.1, LocalStack) are
in [SPECIFICATION-BACKENDS.md §2](SPECIFICATION-BACKENDS.md).

> Corroboration for the pair above: `spring-cloud-build` **5.0.3** — the build parent of Spring
> Cloud 2025.1.3 — declares `<spring-boot.version>4.0.8</spring-boot.version>`. Boot 4.0.8 +
> Cloud 2025.1.3 is therefore the exact combination Spring Cloud itself is built and tested
> against, not merely a compatible one.

> **There is no Spring Cloud "2026.x" train.** The Boot-4 train is versioned `2025.1.x`.
> Declare only `2025.1.3` and let it manage every `spring-cloud-*` version — never pin
> `spring-cloud-*` module versions individually.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.8</version>
</parent>
<properties>
  <java.version>21</java.version>
  <spring-cloud.version>2025.1.3</spring-cloud.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>${spring-cloud.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

---

## 2. Architecture

### 2.1 Component view

```
                        ┌──────────────────────────────┐
   git commit ─────────▶│  config-repo/  (local Git)   │   system of record  (FR-02, CON-04)
        │               │  application.yml             │
        │               │  inventory-service.yml       │
        │               │  pricing-service.yml         │
        │               └───────────────┬──────────────┘
        │                        file:// read
        │  post-commit hook                  │
        │  POST /monitor (FR-16)             ▼
        │               ┌──────────────────────────────────────────┐
        └──────────────▶│  config-server        :8888  mgmt :9888  │
                        │  ├ EnvironmentRepository (Git)           │
                        │  ├ /{app}/{profile}/{label}     (FR-01)  │
                        │  ├ /encrypt  /decrypt           (FR-04)  │
                        │  └ /monitor  (config-monitor)   (FR-06)  │
                        └───────────────┬──────────────────────────┘
                                        │ RefreshRemoteApplicationEvent
                                        │ destination = "inventory-service:**"
                                        ▼
                        ┌──────────────────────────────────────────┐
                        │  RabbitMQ  :5672   (Spring Cloud Bus)    │
                        │  topic exchange: springCloudBus          │
                        └───────┬──────────────────────┬───────────┘
                                │ fanout to subscribers│
              ┌─────────────────▼────────┐  ┌──────────▼───────────────┐
              │ inventory-service :8081  │  │ pricing-service   :8082  │
              │              mgmt :9081  │  │ (+ replica        :8083) │
              │ ├ RefreshListener (bus)  │  │              mgmt :9082  │
              │ ├ ContextRefresher       │  │                          │
              │ ├ ConfigProperties bean  │  │  same client stack       │
              │ └ SnapshotProvider       │  │                          │
              └──────────────────────────┘  └──────────────────────────┘
                    ▲ startup: spring.config.import=configserver:... (FR-20)
```

### 2.2 Propagation sequence (`FR-10`, `NFR-01`, `AC-01`)

```
Operator      git hook        config-server        RabbitMQ        client A        client B
   │              │                 │                 │               │               │
   ├─ commit ────▶│                 │                 │               │               │
   │              ├─ POST /monitor ▶│                 │               │               │
   │              │                 ├ extract changed paths           │               │
   │              │                 ├ map paths → app names (FR-07)   │               │
   │              │                 ├ publish RefreshRemoteApplicationEvent           │
   │              │                 │   destination="inventory-service:**"            │
   │              │                 ├────────────────▶│               │               │
   │              │                 │                 ├── fanout ────▶│               │
   │              │                 │                 ├── fanout ─────────────────────▶│
   │              │◀─ 200 + paths ──┤                 │               │               │
   │              │                 │                 │        ServiceMatcher.isForSelf()
   │              │                 │                 │          A: match → refresh
   │              │                 │                 │          B: no match → ignore
   │              │                 │                 │               │               │
   │              │                 │◀─ GET /inventory-service/default (re-fetch)      │
   │              │                 │                 │               │               │
   │              │                 │        ContextRefresher.refresh():              │
   │              │                 │          1. rebuild Environment                 │
   │              │                 │          2. publish EnvironmentChangeEvent(keys) │
   │              │                 │          3. ConfigurationPropertiesRebinder      │
   │              │                 │             re-initialises props bean in place   │
   │              │                 │          4. RefreshScope.refreshAll()            │
   │              │                 │          5. publish RefreshScopeRefreshedEvent   │
   │              │                 │               │                                  │
   │              │                 │        SnapshotProvider (listener on step 5):    │
   │              │                 │          validate → build immutable snapshot     │
   │              │                 │          → AtomicReference.set (atomic swap)     │
   │              │                 │          → audit + metrics                       │
```

**Why the snapshot listener binds to step 5 and not step 2:** `ConfigurationPropertiesRebinder`
itself listens on `EnvironmentChangeEvent`. Two listeners on the same event have no guaranteed
relative order, so reading the properties bean from an `EnvironmentChangeEvent` handler can
observe pre-rebind values. `RefreshScopeRefreshedEvent` is published *after* rebinding
completes, making it the only ordering-safe hook. The changed-key set from step 2 is captured
into a per-refresh context (lowest-precedence listener) purely for the audit record.

---

## 3. Module layout

```
springboot-external-properties-demo-II/          (parent pom, packaging=pom)
├── pom.xml                                      dependencyManagement, plugin mgmt, quality gates
├── config-repo/                                 local Git repo — the configuration system of record
│   ├── application.yml                          shared by all clients
│   ├── inventory-service.yml
│   ├── inventory-service-dev.yml
│   ├── pricing-service.yml
│   └── .git/hooks/post-commit                   installed by scripts/install-git-hook.sh (FR-16)
├── config-server/                               Spring Cloud Config Server  :8888
│   └── src/main/resources/
│       ├── application.yml                      backend-neutral: security, bus, actuator
│       ├── application-git.yml                  version A — profile `git`
│       ├── application-jdbc.yml                 version B — profile `jdbc`
│       ├── application-awss3.yml                version C — profile `awss3`
│       └── db/migration/                        Flyway V1/V2 (version B only)
├── config-client-commons/                       shared library, jar (not a Boot app)  [Q-02]
│   └── refresh plumbing: snapshot provider base, audit listener, metrics, health, ArchUnit rules
├── inventory-service/                           client 1  :8081
├── pricing-service/                             client 2  :8082 (+ replica :8083)
├── docker/compose.yaml                          RabbitMQ + PostgreSQL + LocalStack (NFR-40, NFR-53)
├── docker/localstack-init/                      bucket, versioning, SQS, notification wiring
├── scripts/
│   ├── install-git-hook.sh
│   ├── encrypt-value.sh
│   └── e2e-acceptance.sh                        scripted AC-01..AC-12
├── REQUIREMENTS.md
├── SPECIFICATION.md
└── README.md
```

Reactor order: `config-client-commons` → `config-server` → `inventory-service` → `pricing-service`.

### 3.1 Package structure (each client)

```
com.example.config.<service>
├── <Service>Application.java
├── config/            InventoryConfigProperties, JacksonConfig, OpenApiConfig, SecurityConfig
├── domain/            immutable records: InventorySettings, ...
├── provider/          InventorySettingsProvider (implements ConfigurationSnapshotProvider)
├── service/           InventoryService  — business logic, reads snapshots
├── web/               InventoryController, ConfigInspectionController
├── web/dto/           request/response DTOs
└── exception/         GlobalExceptionHandler, domain exceptions
```

Dependency direction, enforced by ArchUnit (`NFR-30`): `web → service → provider → domain`.
`domain` depends on nothing. `config` may be depended upon but must not depend on `web` or
`service`.

---

## 4. Configuration repository (`config-repo/`)

### 4.1 Layout and resolution

`application.yml` applies to every client. `<app-name>.yml` applies to that client only.
`<app-name>-<profile>.yml` overlays the profile. Precedence, highest first:
`<app>-<profile>` → `<app>` → `application-<profile>` → `application`.

This matters for `FR-07`: the monitor's default path→application mapping treats
`application.*` as "all applications" and `foo.*` as "application `foo`", which is exactly the
scoping proven by `AC-01` (shared change → both clients) and `AC-02` (scoped change → one client).

### 4.2 Sample content

`config-repo/application.yml` — shared:
```yaml
demo:
  shared:
    banner-message: "Configured centrally via Spring Cloud Config"
    environment-label: "local"
    refresh-audit-enabled: true
```

`config-repo/inventory-service.yml`:
```yaml
inventory:
  warehouse-code: "WH-BLR-01"
  max-order-quantity: 500          # AC-05 flips this to an invalid value
  express-shipping-enabled: false  # AC-04 flips this to true
  low-stock-threshold: 25
  downstream:
    api-key: "{cipher}AQBv7k...."  # AC-08 — never plaintext (NFR-11)
```

`config-repo/pricing-service.yml`:
```yaml
pricing:
  currency: "INR"
  discount-percentage: 10.0
  surge-pricing-enabled: false
  rounding-mode: "HALF_UP"
```

### 4.3 Rationale for `file://` and its consequences (`A-2`, `R-03`, `NFR-13`)

> **[CORRECTED] The repository must be mounted WRITABLE.** Point 1 below is right that only
> committed content is served, but the original spec also assumed the repo could be read-only.
> It cannot: because the Config Server uses the repository path *as its working directory*, it runs
> a real `git checkout <label>` and must create `.git/index.lock`. A read-only mount fails with
> `Read-only file system`, and the server then reports the misleading `No such label: master` as it
> falls back from the default label. `basedir` is ignored for `file:` URIs, so there is no clone to
> isolate the write. The server still never writes *configuration*, so `CON-04` holds; the fix for
> `NFR-51` is a remote `ssh:`/`https:` URI, where the server clones into its own basedir.

With a `file:` URI the Config Server **operates directly on the local repository and does not
clone it into a working-copy cache**. Two consequences are load-bearing:

1. **Only committed content is served.** Editing and saving a file has no effect; the operator
   must `git commit`. This is intentional (`CON-04`) and is what the `post-commit` hook keys off.
2. **`file://` does not scale.** Multiple Config Server replicas would need a shared filesystem.
   For HA (`NFR-13`) the repository must move to `ssh:`/`https:` so each replica clones and
   caches locally, with `spring.cloud.config.server.git.force-pull: true` and a dedicated
   `basedir`. This is a configuration change only (`NFR-41`, `AC-12`).

---

## 5. `config-server` module

### 5.1 Dependencies

| Artifact | Purpose |
|---|---|
| `spring-cloud-config-server` | Environment API, Git backend, encrypt/decrypt (`FR-01`–`FR-04`) |
| `spring-cloud-config-monitor` | `/monitor` webhook receiver (`FR-06`, `FR-07`) |
| `spring-cloud-starter-bus-amqp` | Broadcast transport (`FR-11`) |
| `spring-boot-starter-security` | Authentication on all endpoints (`NFR-10`) |
| `spring-boot-starter-actuator` | Health, metrics (`NFR-20`, `NFR-21`) |
| `spring-boot-starter-validation` | Request validation |

`@EnableConfigServer` on the application class.

### 5.2 `application.yml`

```yaml
server.port: 8888
spring:
  application.name: config-server
  cloud:
    config:
      server:
        git:
          uri: ${CONFIG_REPO_URI:file://${user.home}/.../config-repo}   # FR-05
          default-label: ${CONFIG_REPO_LABEL:main}                       # FR-03
          search-paths: ${CONFIG_REPO_SEARCH_PATHS:}
          clone-on-start: true
          timeout: 10
          # remote-only, kept here for the AC-12 switch:
          # force-pull: true
          # basedir: ${CONFIG_REPO_BASEDIR:/var/tmp/config-repo-cache}
          # username: ${CONFIG_REPO_USERNAME:}
          # password: ${CONFIG_REPO_PASSWORD:}
      # /monitor webhook validation — see §5.4
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  security.user:
    name: ${CONFIG_SERVER_USERNAME:config-admin}
    password: ${CONFIG_SERVER_PASSWORD}          # no default — fail loudly (NFR-11)

encrypt:
  key-store:                                     # asymmetric, production posture (FR-04)
    location: ${ENCRYPT_KEYSTORE_LOCATION:file:./config-server.jks}
    password: ${ENCRYPT_KEYSTORE_PASSWORD}
    alias:    ${ENCRYPT_KEYSTORE_ALIAS:configkey}
    secret:   ${ENCRYPT_KEYSTORE_SECRET}

management:
  server.port: 9888                              # NFR-12
  endpoints.web.exposure.include: health,info,metrics,prometheus,busrefresh,env,configprops
  endpoint.health.show-details: when-authorized
  health.probes.enabled: true
```

A local-only `application-local.yml` may substitute a symmetric `encrypt.key` env var to keep
first-run friction low; the keystore path is the documented default.

### 5.3 Security design (`NFR-10`, `NFR-12`)

| Path | Rule |
|---|---|
| `/actuator/health/**` (mgmt port) | permitAll — probes |
| `/monitor` | authenticated, role `WEBHOOK`; CSRF disabled for this path only (non-browser client) |
| `/encrypt`, `/decrypt` | authenticated, role `CONFIG_ADMIN` |
| `/{app}/{profile}/**` | authenticated, role `CONFIG_CLIENT` |
| everything else | denied |

Stateless (`SessionCreationPolicy.STATELESS`), HTTP Basic. TLS terminates at the ingress
locally; `server.ssl.*` documented for deployment. Credentials come from environment variables
only — `CONFIG_SERVER_PASSWORD` deliberately has **no default** so a misconfigured deployment
fails at startup rather than running open.

### 5.4 `/monitor` and the local-Git bridge (`FR-06`, `FR-07`, `FR-16`)

`spring-cloud-config-monitor` exposes `POST /monitor`, extracts the changed file paths, maps
them to application names, and publishes a `RefreshRemoteApplicationEvent` scoped to those
applications over the Bus.

Two accepted request shapes:

**(a) Form-encoded — used by the local `post-commit` hook.**
```
POST /monitor
Content-Type: application/x-www-form-urlencoded
path=inventory-service
```
`path` supports wildcards and maps directly to a broadcast destination.

**(b) Provider webhook JSON — used after the move to GitHub (`AC-12`).**
```
POST /monitor
X-Github-Event: push
X-Hub-Signature-256: sha256=<hmac>
{"commits":[{"modified":["inventory-service.yml"],"added":[],"removed":[]}]}
```

> **[CORRECTED] Verified false for unsigned requests.** On spring-cloud-config 5.0.5, `/monitor`
> returned **200 and broadcast** for BOTH the form-encoded and the GitHub-shaped payload with
> `validation-filter-enabled: true` and **no** webhook secret configured. Task `T-06` is therefore
> resolved: no local relaxation is needed, and the implementation keeps the secure default. A
> `webhook-secret` is still required for a remote GitHub repo so pushes are authenticated by
> signature. The original claim, retained below for context, was:
>
> ~~with no webhook secret configured, `/monitor` **rejects all provider webhook requests**.~~ Therefore `spring.cloud.config.server.monitor.github.webhook-secret`
> must be set for the remote path. Relaxations
> (`spring.cloud.config.server.monitor.validation-filter-enabled: false`, or the per-provider
> `validation-enabled: false`) are permitted **only** in the `local` profile and must never
> appear in a deployed profile. Implementation task `T-06` confirms empirically whether shape
> (a) traverses the validation filter; if it does, the local profile uses the signed shape (b)
> with a local secret rather than disabling validation.

`config-repo/.git/hooks/post-commit`, installed by `scripts/install-git-hook.sh`:

```sh
#!/bin/sh
# Bridges a local commit to the Config Server webhook (FR-16).
CHANGED=$(git diff-tree -r --no-commit-id --name-only HEAD)
CORRELATION_ID=$(git rev-parse --short HEAD)
for f in $CHANGED; do
  app=$(basename "$f" | sed -E 's/\.(ya?ml|properties)$//; s/-[a-z0-9]+$//')
  curl -sf -u "$CONFIG_SERVER_USERNAME:$CONFIG_SERVER_PASSWORD" \
       -H "X-Correlation-Id: $CORRELATION_ID" \
       -d "path=$app" "$CONFIG_SERVER_MONITOR_URL" \
    || echo "post-commit: monitor notify failed for $app" >&2
done
```
The hook is best-effort: a failed notify never blocks the commit, and manual broadcast
(`FR-13`) remains the recovery path.

### 5.5 Custom extension point

`PropertyPathNotificationExtractor` is implemented (`CorrelatedPathNotificationExtractor`) to
attach the `X-Correlation-Id` header to the outgoing bus event, satisfying end-to-end
correlation in `NFR-22` / `AC-09`. This is the Adapter pattern applied at the webhook boundary.

---

## 6. Client modules

### 6.1 Dependencies

| Artifact | Purpose |
|---|---|
| `spring-boot-starter-web` | REST endpoints |
| `spring-cloud-starter-config` | Config-data import from Config Server (`FR-20`) |
| `spring-cloud-starter-bus-amqp` | Receives refresh broadcasts (`FR-11`, `FR-12`) |
| `spring-boot-starter-actuator` | `refresh`, `busrefresh`, health, metrics (`FR-13`) |
| `spring-boot-starter-validation` | `@Validated` configuration binding (`FR-21`) |
| `spring-boot-configuration-processor` (optional) | Generates `spring-configuration-metadata.json` (`NFR-33`) |
| ~~`spring-retry` + `spring-boot-starter-aop`~~ | **[CORRECTED] Not required, and not available.** Retry rides on the `spring.config.import` URI and is handled by the config-data implementation; these two are needed only by the legacy bootstrap path (`CON-06`). `spring-boot-starter-aop` was **removed in Spring Boot 4** — its last GA release is 3.5.16. |
| `springdoc-openapi-starter-webmvc-ui` | API docs (consistent with sibling project) |
| `config-client-commons` | Shared refresh plumbing |

### 6.2 `application.yml` (inventory-service shown)

```yaml
server.port: ${SERVER_PORT:8081}
spring:
  application:
    name: inventory-service
    index: ${APP_INDEX:${server.port}}       # bus id uniqueness for the AC-03 replica
  config:
    import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}?fail-fast=true&max-attempts=6&initial-interval=1000&multiplier=1.5&max-interval=8000"
  cloud:
    config:
      username: ${CONFIG_SERVER_USERNAME:config-client}
      password: ${CONFIG_SERVER_PASSWORD}
      label: ${CONFIG_LABEL:main}
      request-connect-timeout: 5000
      request-read-timeout: 15000
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
management:
  server.port: ${MANAGEMENT_PORT:9081}
  endpoints.web.exposure.include: health,info,metrics,prometheus,refresh,busrefresh,configprops,env
  endpoint:
    health.show-details: when-authorized
    env.show-values: never                   # NFR-12 masking
    configprops.show-values: never
  health.probes.enabled: true
```

Notes:
- `spring.config.import` **without** `optional:` → hard failure if the server is unreachable,
  which together with the retry parameters is exactly `NFR-14` / `AC-07`.
- No `bootstrap.yml` (`CON-06`).
- `spring.application.index` is set so the Bus service id (`app:index:id`) is unique across the
  two `pricing-service` instances, preventing duplicate-event suppression from silently
  dropping a refresh on one of them (`AC-03`).

### 6.3 The refresh-safe configuration pattern (`FR-21`, `FR-22`, `FR-30`, `FR-32`, `CON-03`)

This is the core design decision of the project. Three collaborating pieces:

> **[CORRECTED] `@Validated` must be REMOVED from the properties class.** The snippet below
> originally carried `@Validated`. Reading `ConfigurationPropertiesRebinder.rebind` shows it
> records the failure and then **rethrows**, so a constraint violation during rebind propagates out
> of `ContextRefresher.refresh()` and `RefreshScopeRefreshedEvent` is never published at all —
> destroying the very last-known-good and observability guarantees `FR-30` asks for. Validation is
> driven by the provider instead (step 3), which validates the already-rebound bean and rejects the
> refresh without breaking the chain. Startup fail-fast is preserved by validating in
> `@PostConstruct`.

**(1) Rebindable properties holder — setter-based, mandatory.**

```java
@Component
@ConfigurationProperties(prefix = "inventory")
// NOTE: deliberately NOT @Validated - see the correction above.
public class InventoryConfigProperties {
    @NotBlank  private String warehouseCode;
    @Min(1) @Max(10_000) private int maxOrderQuantity;
    private boolean expressShippingEnabled;
    @Min(0) private int lowStockThreshold;
    @Valid @NotNull private Downstream downstream = new Downstream();
    // getters + setters — setters are REQUIRED, see below
}
```

`ConfigurationPropertiesRebinder` re-initialises the **existing** bean instance on
`EnvironmentChangeEvent`, which works only through setters. A `record` or any
constructor-bound class is re-created instead, so every reference injected before the refresh
keeps stale values — a confirmed, long-standing limitation
([spring-cloud-config#1547](https://github.com/spring-cloud/spring-cloud-config/issues/1547),
[#1851](https://github.com/spring-cloud/spring-cloud-config/issues/1851)). Hence `CON-03`,
risk `R-01`, and an ArchUnit rule that fails the build on a record or setter-less
`@ConfigurationProperties` type.

**(2) Immutable domain snapshot — what business code actually sees.**

```java
public record InventorySettings(
        String warehouseCode,
        int maxOrderQuantity,
        boolean expressShippingEnabled,
        int lowStockThreshold,
        long version,
        Instant appliedAt) {}
```

**(3) Provider — validate, then atomically swap; keep last-known-good on failure.**

```java
@Component
public class InventorySettingsProvider
        implements ConfigurationSnapshotProvider<InventorySettings>,
                   ApplicationListener<RefreshScopeRefreshedEvent> {

    private final AtomicReference<InventorySettings> current = new AtomicReference<>();
    private final AtomicLong version = new AtomicLong();
    private final InventoryConfigProperties properties;
    private final Validator validator;
    private final ConfigRefreshMetrics metrics;
    private final ConfigRefreshAuditor auditor;

    @PostConstruct
    void initialise() { current.set(build()); }          // fail fast at startup

    @Override
    public InventorySettings get() { return current.get(); }   // lock-free read (NFR-03, FR-32)

    @Override
    public void onApplicationEvent(RefreshScopeRefreshedEvent event) {
        var timer = metrics.startRefresh();
        try {
            var candidate = build();                     // throws on constraint violation
            var previous  = current.getAndSet(candidate);
            auditor.recordApplied(previous, candidate);  // key names only (FR-31)
            metrics.recordSuccess(timer);
        } catch (ConfigurationValidationException ex) {
            auditor.recordRejected(ex);
            metrics.recordFailure(timer, ex);            // AC-05: snapshot untouched
        }
    }
}
```

Why this shape:
- **Atomicity (`FR-32`, `AC-10`).** A request reads one `AtomicReference` once. It sees the old
  or the new snapshot in full — never a half-applied set of fields, which is precisely what a
  directly-injected mutable properties bean *would* expose mid-rebind.
- **Last-known-good (`FR-30`, `AC-05`).** Validation runs on the candidate before the swap. A
  bad commit is rejected and the service keeps serving.
- **No `@RefreshScope` on business beans (`R-04`).** Services stay plain singletons; nothing is
  destroyed and re-created on refresh, so there is no latency spike and no risk of leaking
  heavyweight resources. `@RefreshScope` is reserved for the narrow cases in §6.5.
- **`@Value` is banned** for refreshable keys (`R-02`), enforced by ArchUnit.

### 6.4 Business behaviour that visibly changes (`FR-24`, `AC-04`)

| Service | Config key | Observable effect |
|---|---|---|
| inventory | `inventory.express-shipping-enabled` | Feature flag gating an `expressEligible` field and an alternate code path in the reservation response. |
| inventory | `inventory.max-order-quantity` | Requests above the limit are rejected with `422`; raising the limit makes the same request succeed with no restart. |
| pricing | `pricing.discount-percentage` | Quoted price changes on the next request. |
| pricing | `pricing.surge-pricing-enabled` | Adds a surge component to the quote. |

`GET /api/v1/config/snapshot` on each client returns the effective snapshot with `version` and
`appliedAt` (`FR-23`), which is what the acceptance scripts assert against.

### 6.5 Where `@RefreshScope` *is* used

Only for beans whose construction depends on configuration and which are cheap to rebuild —
e.g. a `RestClient` built with a config-driven base URL and timeout. Rules:
- Must be a `@Bean` factory method annotated `@RefreshScope` (component-scanned
  `@ConfigurationProperties` classes are not reliably placed in the refresh scope).
- Must not hold a connection pool, thread pool, or any resource requiring orderly shutdown.
- Each usage carries a comment justifying it. Anything else is a review rejection.

---

## 7. Design patterns and standards applied

Requirement `NFR-30`, and the user's "all standards, design patterns" objective, mapped to
concrete artefacts rather than named in the abstract.

| Pattern | Where | Why it is the right fit |
|---|---|---|
| **Externalized Configuration** (12-Factor III) | Whole system | Config lives outside the artefact; the same binary runs in every environment. |
| **Publish–Subscribe / Observer** | Bus `RefreshRemoteApplicationEvent`; `ApplicationListener<RefreshScopeRefreshedEvent>` | One publisher, N unknown subscribers — the only shape that satisfies `FR-12` without the server knowing its clients. |
| **Provider / Facade** | `ConfigurationSnapshotProvider<T>` | Single seam between mutable Spring binding and immutable domain state; lets §6.3 hold the entire refresh concern in one place. |
| **Immutable Value Object** | `InventorySettings`, `PricingSettings` records | Thread-safe reads with no defensive copying (`NFR-03`). |
| **Atomic swap / copy-on-write** | `AtomicReference<T>` in each provider | Lock-free consistency under concurrent load (`FR-32`, `AC-10`). |
| **Strategy** | `EnvironmentRepository` (Git / JDBC / S3) selected by profile; `ConfigChangeDetector` per backend; Bus binder (AMQP / Kafka) | Backend, change-detection mechanism, and transport are each swappable without touching clients (`NFR-41`, `NFR-42`, `FR-40`, `FR-41`). |
| **Adapter** | `CorrelatedPathNotificationExtractor`; the `post-commit` hook | Translates foreign webhook/Git shapes into the internal event model. |
| **Proxy** | `@RefreshScope` scoped proxies (§6.5) | Deferred, refreshable resolution behind a stable reference. |
| **Template Method** | `AbstractConfigurationSnapshotProvider` in commons | Fixes the validate → swap → audit → meter algorithm; subclasses supply only `build()`. |
| **Repository** | `EnvironmentRepository`, Git as store | Configuration retrieval abstracted from its storage. |
| **DTO + Mapper** | `web/dto` + MapStruct | Domain records never leak to the wire; response shape can evolve independently. |
| **Builder** | Response DTOs, error payloads | Readable construction of wide, mostly-optional objects. |
| **Retry with exponential backoff** | Config-data import retry params (§6.2) | Startup ordering is not guaranteed; a transient server start-up gap must not kill the client. |
| **Fail-fast** | `spring.config.import` without `optional:`; no default for password env vars | A misconfigured service must not serve traffic in an unknown state (`NFR-14`, `AC-07`). |
| **Graceful degradation** | Broker down → serve current config, reconnect (`NFR-15`) | Propagation is best-effort; availability is not. |
| **Layered architecture + Dependency Inversion** | `web → service → provider → domain`, ArchUnit-enforced | Compile-time-checked, not convention-based (`NFR-30`). |
| **Single Responsibility / SoC** | Refresh, audit, metrics, health each a distinct commons class | Each has one reason to change. |

Coding standards: Google Java Format via Spotless; Checkstyle; SpotBugs; Javadoc on all public
API; Jakarta Bean Validation for every externally supplied value; `@RestControllerAdvice`
global exception handling returning RFC 9457 `ProblemDetail`; API versioned under `/api/v1`.

---

## 8. Cross-cutting concerns

### 8.1 Observability (`NFR-20`, `NFR-21`, `NFR-22`, `AC-09`)

Metrics (`ConfigRefreshMetrics`, Micrometer):

| Meter | Type | Tags |
|---|---|---|
| `config.refresh.attempts` | counter | `trigger` = webhook \| bus \| manual |
| `config.refresh.success` | counter | `application` |
| `config.refresh.failures` | counter | `reason` = validation \| fetch \| transport |
| `config.refresh.duration` | timer | `application` |
| `config.snapshot.version` | gauge | current snapshot version |
| `config.refresh.age.seconds` | gauge | seconds since last successful refresh |

Health (`ConfigurationHealthIndicator`, contributes to `/actuator/health`):
`UP` with `lastRefreshAt`, `snapshotVersion`, `lastOutcome`, `brokerConnected`;
`DEGRADED`-as-`UP`-with-details when the broker is disconnected but config is valid (serving is
unaffected — `NFR-15`); `DOWN` when the startup snapshot could never be built.

Audit (`ConfigRefreshAuditor`) — one structured JSON line per refresh:
```json
{"event":"config.refresh.applied","correlationId":"a1b2c3d","trigger":"webhook",
 "application":"inventory-service","instance":"inventory-service:8081",
 "changedKeys":["inventory.max-order-quantity"],"fromVersion":4,"toVersion":5,
 "outcome":"APPLIED","durationMs":38,"timestamp":"2026-08-30T10:15:04.221Z"}
```
`changedKeys` carries **names only**. Values are never logged (`FR-31`) — the same commit may
carry a `{cipher}` secret. Logback with size-and-time rolling, capped history, matching the
sibling project's conventions.

### 8.2 Secret handling (`NFR-11`, `AC-08`)

Repository stores `{cipher}<base64>`; the server decrypts before serving, so clients see
plaintext in memory only. Keys live in a keystore supplied by environment variable. `scripts/encrypt-value.sh`
wraps `POST /encrypt`. A CI check greps the repository for high-entropy strings and known secret
patterns and fails on a hit.

### 8.3 Resilience summary

| Failure | Behaviour | Requirement |
|---|---|---|
| Config Server down at client startup | Retry 6× with backoff (1s → 8s, ×1.5), then fail fast with a clear cause | `NFR-14`, `AC-07` |
| Config Server down after startup | Client keeps its snapshot; refresh attempts fail, counted and surfaced in health | `NFR-15` |
| Broker down | No propagation; serving unaffected; auto-reconnect; manual `/actuator/refresh` fallback | `NFR-15`, `AC-06` |
| Git unavailable | Server serves last read state where possible; health `DOWN` otherwise | `NFR-16` |
| Invalid property value committed | Candidate rejected at validation; last-known-good retained; failure metered | `FR-30`, `AC-05` |
| Refresh with no effective change | No-op short-circuit; snapshot version unchanged | `FR-15` |
| Duplicate bus events | Suppressed by `ServiceMatcher` on unique `spring.cloud.bus.id` | `FR-12` |

---

## 9. API surface

### 9.1 config-server (`:8888`, mgmt `:9888`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/{application}/{profile}[/{label}]` | Environment API |
| GET | `/{application}-{profile}.yml` \| `.properties` \| `.json` | Rendered forms |
| POST | `/encrypt`, `/decrypt` | Cipher management |
| POST | `/monitor` | Webhook receiver |
| POST | `/actuator/busrefresh[/{destination}]` | Manual cluster-wide broadcast (`FR-13`) |
| GET | `/actuator/health`, `/actuator/prometheus` | Ops |

### 9.2 Clients (`:8081` / `:8082`, mgmt `:9081` / `:9082`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/config/snapshot` | Effective snapshot + version + `appliedAt` (`FR-23`) |
| GET | `/api/v1/config/history` | Last N applied refresh audit records |
| POST | `/api/v1/inventory/reservations` | Business call gated by the live config (`FR-24`) |
| GET | `/api/v1/pricing/quotes/{sku}` | Business call using live discount/surge config |
| POST | `/actuator/refresh` | Local-instance refresh; returns changed keys (`FR-13`, `FR-14`) |
| POST | `/actuator/busrefresh[/{destination}]` | Broadcast; `destination` supports `pricing-service:**` |
| GET | `/actuator/health`, `/swagger-ui.html` | Ops / docs |

---

## 10. Testing strategy (`NFR-32`, `AC-11`)

| Level | Tooling | Coverage |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | Provider swap logic, validation rejection, business gating on flags, path→app mapping. |
| Configuration binding | `@SpringBootTest` slices, `ApplicationContextRunner` | Constraint violations rejected at startup; metadata generated. |
| Config Server | JGit-created temp repo in `@TempDir` | Serves per-app/per-profile/per-label content; `{cipher}` decryption; `/monitor` extracts and scopes correctly. |
| Bus integration | **Testcontainers RabbitMQ**, two client contexts on distinct ports | The decisive test: commit → `/monitor` → both contexts observe a new snapshot version within the `NFR-01` budget. |
| Resilience | Testcontainers pause/stop, WireMock for the server | `AC-05`, `AC-06`, `AC-07`. |
| Concurrency | Sustained concurrent load during an applied refresh | `AC-10`: zero failures, every response consistent with one snapshot. |
| Architecture | **ArchUnit** | Layer direction; no `@Value` in refreshable packages; no record/setter-less `@ConfigurationProperties`; `@RefreshScope` only on approved `@Bean` methods. |
| End-to-end | `scripts/e2e-acceptance.sh` | Scripted `AC-01`…`AC-12` against a running compose stack. |

Gates: JaCoCo 80% line / 70% branch (`Q-03`), Spotless check, Checkstyle, SpotBugs, OWASP
dependency-check, `mvn verify` fails on any violation (`NFR-31`).

---

## 11. Local runtime (`NFR-40`)

```bash
docker compose -f docker/compose.yaml up -d     # RabbitMQ + management UI :15672
./scripts/install-git-hook.sh                   # bridge commits → /monitor
mvn -q clean install
mvn -pl config-server     spring-boot:run
mvn -pl inventory-service spring-boot:run
mvn -pl pricing-service   spring-boot:run
mvn -pl pricing-service   spring-boot:run -Dspring-boot.run.arguments="--server.port=8083 --management.server.port=9083 --spring.application.index=8083"
```

Demonstrating the goal end to end:
```bash
curl -s localhost:8081/api/v1/config/snapshot | jq .        # version N
vim config-repo/inventory-service.yml                       # flip express-shipping-enabled
git -C config-repo commit -am "enable express shipping"      # post-commit fires /monitor
curl -s localhost:8081/api/v1/config/snapshot | jq .        # version N+1, no restart
curl -s localhost:8082/api/v1/config/snapshot | jq .        # unchanged — scoped broadcast
```

---

## 12. Implementation plan

| ID | Task | Proves |
|---|---|---|
| `T-01` | Parent POM: BOM import, Java 21, plugin management, quality gates | `CON-01`, `CON-02`, `NFR-31` |
| `T-02` | `config-repo` seeded and initialised as a Git repo | `FR-02` |
| `T-03` | `config-server` with Git backend + Environment API | `FR-01`–`FR-03`, `FR-05` |
| `T-04` | Encryption keystore + `/encrypt` + a `{cipher}` value in the repo | `FR-04`, `NFR-11`, `AC-08` |
| `T-05` | Config Server security | `NFR-10`, `NFR-12` |
| `T-06` | `/monitor` + Bus/RabbitMQ wiring; **resolve the validation-filter question in §5.4** | `FR-06`, `FR-07`, `FR-11` |
| `T-07` | `post-commit` hook + installer | `FR-16` |
| `T-08` | `config-client-commons`: provider template, auditor, metrics, health, ArchUnit rules | `FR-22`, `FR-30`–`FR-32`, `NFR-20`–`NFR-22`, `NFR-30` |
| `T-09` | `inventory-service` end to end | `FR-20`–`FR-24`, `AC-01`, `AC-04` |
| `T-10` | `pricing-service` + replica | `FR-25`, `AC-02`, `AC-03` |
| `T-11` | Resilience paths (retry, fail-fast, broker-down, bad config) | `NFR-14`–`NFR-16`, `AC-05`–`AC-07` |
| `T-12` | Test suite incl. Testcontainers broadcast test and concurrency test | `NFR-32`, `AC-10`, `AC-11` |
| `T-13` | Compose, scripts, README, remote-Git switch documented | `NFR-40`, `NFR-41`, `AC-12` |
| `T-14` | Boot 4.1.x upgrade note (§13) | `CON-01` |

Tasks `T-15`…`T-X-03` (change-detection SPI extraction, then the PostgreSQL and S3 backends) are
in [SPECIFICATION-BACKENDS.md §10](SPECIFICATION-BACKENDS.md). Version A lands first and is the
reference implementation; `T-15` refactors the Git path onto the SPI so B and C are purely
additive.

---

## 13. Upgrade path to Spring Boot 4.1.x

Spring Cloud `2025.1.3` supports Boot 4.1.x (from `2025.1.2`). To move:

1. Bump `spring-boot-starter-parent` to `4.1.1`; leave `spring-cloud.version` at `2025.1.3`.
2. Re-run `mvn dependency:tree` and confirm no `spring-cloud-*` version was overridden by hand.
3. Re-run the Testcontainers broadcast test — it is the canary for any change in refresh
   ordering or actuator endpoint behaviour.
4. Review the Boot 4.1 release notes for actuator exposure and `ProblemDetail` changes.

Not pursued now because 4.0.x is Oakwood's primary tested baseline and has materially better
documentation coverage for Config Server (`CON-01`, `R-07`).

---

## 14. Traceability (`NFR-34`)

| Requirement | Design | Test |
|---|---|---|
| `FR-01`–`FR-03` | §5.1, §5.2 | `T-03` server tests |
| `FR-04` | §5.2, §8.2 | `AC-08` |
| `FR-05` | §5.2 env indirection | `AC-12` |
| `FR-06`, `FR-07` | §5.4, §5.5 | `AC-01`, `AC-02` |
| `FR-10`–`FR-12` | §2.2, §6.1, §6.2 | `AC-01`, `AC-03` |
| `FR-13`, `FR-14` | §9.1, §9.2 actuator | `AC-06` |
| `FR-15` | §8.3 no-op short-circuit | `T-08` unit |
| `FR-16` | §5.4 hook | `AC-01` |
| `FR-20`, `FR-21` | §6.2, §6.3(1) | binding slice tests |
| `FR-22`, `FR-32` | §6.3(2)(3) | `AC-10` |
| `FR-23`, `FR-24` | §6.4, §9.2 | `AC-04` |
| `FR-25` | §3 modules | `AC-02` |
| `FR-30`, `FR-31` | §6.3(3), §8.1 | `AC-05`, `AC-09` |
| `NFR-01`–`NFR-03` | §2.2, §6.3 lock-free read | Testcontainers timing assert, `AC-10` |
| `NFR-10`–`NFR-12` | §5.3, §6.2 masking, §8.2 | `AC-08` |
| `NFR-13`–`NFR-16` | §4.3, §8.3 | `AC-06`, `AC-07` |
| `NFR-20`–`NFR-22` | §8.1 | `AC-05`, `AC-09` |
| `NFR-30`–`NFR-34` | §3.1, §7, §10 | `AC-11`, this table |
| `NFR-40`–`NFR-42` | §11, §4.3, §7 Strategy | `AC-12` |
| `CON-01`, `CON-02` | §1 | build |
| `CON-03` | §6.3(1) | ArchUnit rule |
| `CON-04`–`CON-06` | §4.3, §6.2, §6.5 | review |
| `FR-40`–`FR-49`, `NFR-50`–`NFR-54` | [SPECIFICATION-BACKENDS.md](SPECIFICATION-BACKENDS.md) | `AC-13`–`AC-23` |

---

## 15. Sources

- [Spring Cloud project page — release train / Boot compatibility](https://spring.io/projects/spring-cloud)
- [Spring Cloud release train supported versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)
- [Spring Cloud Config — Client (`spring.config.import`, retry, fail-fast)](https://docs.spring.io/spring-cloud-config/reference/client.html)
- [Spring Cloud Config — Git backend (`file://` behaviour, force-pull, search-paths)](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/git-backend.html)
- [Spring Cloud Config — Push notifications and Bus (`/monitor`)](https://docs.spring.io/spring-cloud-config/reference/server/push-notifications-and-bus.html)
- [Spring Cloud Bus — Bus endpoints](https://docs.spring.io/spring-cloud-bus/reference/spring-cloud-bus/bus-endpoints.html)
- [Spring Cloud Bus — Addressing instances (`app:index:id`)](https://docs.spring.io/spring-cloud-bus/reference/spring-cloud-bus/addressing.html)
- [Spring Cloud Commons — Application context services (`RefreshScope`, rebinder)](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html)
- Constructor-binding refresh limitation: [spring-cloud-config#1547](https://github.com/spring-cloud/spring-cloud-config/issues/1547), [#1851](https://github.com/spring-cloud/spring-cloud-config/issues/1851), [spring-cloud-commons#846](https://github.com/spring-cloud/spring-cloud-commons/issues/846)
