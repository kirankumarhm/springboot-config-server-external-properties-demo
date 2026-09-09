# Requirements — Dynamic External Configuration with Spring Cloud Config

| Field | Value |
|---|---|
| Project | `springboot-external-properties-demo-II` |
| Version | 1.0 (draft for approval) |
| Date | 2026-08-30 |
| Author | kirankumarhm2004@gmail.com |
| Status | Awaiting sign-off |
| Related | Sibling project `springboot-external-properties-demo` (file-based external properties, no Config Server) |
| Addendum | [SPECIFICATION-BACKENDS.md](SPECIFICATION-BACKENDS.md) — backend variants B (PostgreSQL/JDBC) and C (AWS S3), incl. requirements `FR-40`–`FR-49`, `NFR-50`–`NFR-54`, `AC-13`–`AC-23` |

---

## 1. Purpose

Build a production-grade, multi-module Spring Boot 4 system in which **all externalized
configuration is owned by a Spring Cloud Config Server**, and in which a change committed to
the configuration repository is **pushed to every running client instance within seconds,
with no restart and no redeploy**.

This project supersedes the sibling `springboot-external-properties-demo`, which loaded
external properties from a local file and required a manual trigger. The gap being closed is
**centralised ownership + automatic propagation**.

## 2. Scope

### 2.1 In scope

- A Config Server serving per-application and per-profile properties from **three interchangeable
  backends**, selected by Spring profile: Git (version A), PostgreSQL/JDBC (version B), and
  AWS S3 (version C). Detail in [SPECIFICATION-BACKENDS.md](SPECIFICATION-BACKENDS.md).
- Two independent client services, each consuming config from the server and each refreshing live.
- Automatic, event-driven propagation of changes (no client-side polling).
- Encryption of sensitive property values at rest in the repository.
- Production concerns: security, observability, resilience, testing, containerised local runtime.

### 2.2 Out of scope

- Service discovery (Eureka) and API gateway — not required by the configuration goal.
- Kubernetes ConfigMap / HashiCorp Vault / Redis backends. The `EnvironmentRepository` seam makes
  these additive, but they are not implemented.
- A UI for editing configuration. The backend store is the system of record and the only write
  path; the Config Server never writes to it.
- Business functionality beyond what is needed to observe configuration taking effect.

### 2.3 Assumptions

- `A-1` Local development on macOS (darwin), Java 21, Maven 3.9.x, Docker available for RabbitMQ,
  PostgreSQL, and LocalStack.
- `A-2` The configuration Git repository is local (`file://`) for this phase; the URI and
  credentials are fully externalized so a move to remote GitHub is a property change only.
- `A-4` Version A (Git) is delivered first and is the reference implementation. Versions B and C
  are additive behind the change-detection SPI and must not require any client change.
- `A-3` A single Config Server instance is acceptable for local/demo. Requirement `NFR-13`
  records what must change for a highly-available deployment.

---

## 3. Stakeholders and actors

| Actor | Interest |
|---|---|
| Configuration Operator | Commits a property change and expects it live in seconds without a deploy. |
| Application Developer | Consumes strongly-typed, validated configuration; must not hand-roll refresh logic. |
| Platform / SRE | Needs auditability of who changed what and when, health signals, and safe failure modes. |
| Security Reviewer | Requires no plaintext secrets in Git, authenticated config endpoints, masked telemetry. |

---

## 4. Functional requirements

### 4.1 Configuration Server

| ID | Requirement | Priority |
|---|---|---|
| `FR-01` | The system SHALL provide a Spring Cloud Config Server exposing the standard Environment API (`/{application}/{profile}[/{label}]`). | Must |
| `FR-02` | The server SHALL source properties from a Git repository, resolving per-application files, per-profile overlays, and a shared `application.yml` applied to all clients. | Must |
| `FR-03` | The server SHALL support Git *labels* (branch/tag) so a client can be pinned to a specific configuration revision. | Must |
| `FR-04` | The server SHALL expose `/encrypt` and `/decrypt` so operators can produce `{cipher}` values, and SHALL transparently decrypt `{cipher}` values before serving them. | Must |
| `FR-05` | The Git repository URI, search paths, label, and credentials SHALL be externalized (env vars / `--` args) with no repository-specific value hard-coded in the image. | Must |
| `FR-06` | The server SHALL expose a webhook receiver endpoint that accepts a repository push notification and converts it into a cluster-wide refresh broadcast. | Must |
| `FR-07` | The webhook receiver SHALL derive the set of affected application names from the changed file paths, and SHALL scope the broadcast to only those applications. | Should |

### 4.2 Change propagation

| ID | Requirement | Priority |
|---|---|---|
| `FR-10` | A configuration change committed to the repository SHALL propagate to all running client instances **without operator action on the clients** and **without restarting them**. | Must |
| `FR-11` | Propagation SHALL be push-based over a message broker (publish/subscribe), not client-side polling. | Must |
| `FR-12` | A single broadcast SHALL reach **every instance** of a client application, including horizontally scaled replicas. | Must |
| `FR-13` | The system SHALL also support a **manually triggered** refresh (a single-instance local refresh and a cluster-wide broadcast) for operator recovery when the webhook path is unavailable. | Must |
| `FR-14` | A refresh SHALL report which property keys changed, so the effect of a commit is observable. | Must |
| `FR-15` | A refresh that changes no property values SHALL be a no-op that does not re-initialise client beans unnecessarily. | Should |
| `FR-16` | Because a `file://` repository emits no webhooks, the system SHALL provide a Git `post-commit` hook that invokes the webhook receiver, so committing locally behaves like a remote push. | Must |

### 4.3 Client services

| ID | Requirement | Priority |
|---|---|---|
| `FR-20` | Each client SHALL fetch its configuration from the Config Server at startup using Spring Boot's config-data import mechanism (`spring.config.import`). | Must |
| `FR-21` | Configuration SHALL be bound to **strongly-typed, validated** configuration classes. Invalid configuration SHALL be rejected. | Must |
| `FR-22` | Business components SHALL observe post-refresh values through an **immutable snapshot** obtained from a provider, never by caching a mutable configuration reference at construction time. | Must |
| `FR-23` | Each client SHALL expose a read endpoint returning its currently effective configuration snapshot, including the snapshot version and the timestamp of the last applied refresh. | Must |
| `FR-24` | Each client SHALL demonstrate at least one behaviour that visibly changes when configuration changes (e.g. a feature flag gating a code path, and a numeric limit enforced by a service). | Must |
| `FR-25` | Two distinct client services SHALL be implemented so that scoped and broadcast refresh can be distinguished and proven. | Must |
| `FR-26` | A client SHALL start successfully and serve traffic using safe defaults if the Config Server is reachable but a *non-critical* optional property is absent. | Should |

### 4.4 Validation and safety

| ID | Requirement | Priority |
|---|---|---|
| `FR-30` | If a refresh delivers configuration that fails validation, the client SHALL retain the **last known-good** snapshot, remain serving, and surface the failure via health and metrics. It SHALL NOT adopt partially-bound configuration. | Must |
| `FR-31` | Every applied refresh SHALL emit a structured audit record: timestamp, trigger source, changed key names, and outcome. Property **values** SHALL NOT be logged. | Must |
| `FR-32` | Configuration reads SHALL be safe under concurrent request load while a refresh is in progress — a request SHALL see either the old or the new snapshot in full, never a mix. | Must |

---

## 5. Non-functional requirements

### 5.1 Performance

| ID | Requirement |
|---|---|
| `NFR-01` | End-to-end propagation latency, from webhook receipt at the server to the new snapshot being served by a client, SHALL be **≤ 2 s at p95** and **≤ 5 s at p99** for the local topology. |
| `NFR-02` | A refresh SHALL NOT drop or fail in-flight HTTP requests on the client. |
| `NFR-03` | Reading the configuration snapshot on a request path SHALL be a non-blocking, allocation-free field read (no lock contention, no property re-parse per request). |

### 5.2 Security

| ID | Requirement |
|---|---|
| `NFR-10` | The Config Server's Environment API, `/encrypt`, `/decrypt`, and webhook endpoint SHALL require authentication. Clients SHALL authenticate with credentials supplied from the environment. |
| `NFR-11` | No secret SHALL exist in plaintext in the configuration repository, in application code, or in any committed file. Secrets in Git SHALL be `{cipher}` values; the decryption key SHALL be supplied only via environment/keystore. |
| `NFR-12` | Actuator endpoints SHALL be exposed on a separate management port, restricted to an authenticated role, with an explicit allow-list of endpoints. `/actuator/env` and `/actuator/configprops` output SHALL be masked for sensitive keys. |

### 5.3 Reliability and resilience

| ID | Requirement |
|---|---|
| `NFR-13` | The design SHALL document the exact changes required for a highly-available Config Server (shared/remote repository over `ssh:`, multiple replicas, `force-pull`), even though one instance is deployed locally. |
| `NFR-14` | If the Config Server is unavailable at client startup, the client SHALL retry with exponential backoff and then fail fast with a clear diagnostic rather than start in an unconfigured state. |
| `NFR-15` | If the message broker is unavailable, clients SHALL continue serving with their current configuration, SHALL reconnect automatically when it returns, and the condition SHALL be visible in health. Manual refresh (`FR-13`) remains the fallback. |
| `NFR-16` | If the Git backend is unavailable, the Config Server SHALL serve the last successfully read state where possible and SHALL report unhealthy otherwise. |

### 5.4 Observability

| ID | Requirement |
|---|---|
| `NFR-20` | Each service SHALL expose liveness/readiness health, and a custom health indicator reporting configuration state (last refresh time, last refresh outcome, broker connectivity). |
| `NFR-21` | Metrics SHALL include refresh attempt count, success/failure count, refresh duration, and time since last successful refresh — all Micrometer-registered and Prometheus-scrapable. |
| `NFR-22` | Logs SHALL be structured, include a correlation identifier propagated from the triggering webhook through the broadcast to each client's applied refresh, and be rotated with size and history limits. |

### 5.5 Maintainability and code quality

| ID | Requirement |
|---|---|
| `NFR-30` | Code SHALL follow a layered architecture with enforced dependency direction (controller → service → provider/domain; no reverse or skip dependencies). Enforcement SHALL be automated, not conventional. |
| `NFR-31` | Build SHALL enforce formatting, static analysis, and a minimum line/branch coverage gate. A violation SHALL fail the build. |
| `NFR-32` | Test coverage SHALL include unit tests, an integration test proving broadcast refresh against a real broker, and a test proving `FR-30` (bad config rejected, last-known-good retained). |
| `NFR-33` | All configuration keys SHALL be documented and discoverable via generated metadata (configuration processor), so IDE completion works for consumers. |
| `NFR-34` | Every requirement in this document SHALL map to at least one design element and one test. Untraced requirements SHALL be treated as defects. |

### 5.6 Portability

| ID | Requirement |
|---|---|
| `NFR-40` | Local runtime dependencies (broker) SHALL be brought up by a single `docker compose` command. |
| `NFR-41` | Switching the configuration backend from local Git to remote GitHub SHALL require configuration changes only — no code change and no recompile. |
| `NFR-42` | Switching the broker from RabbitMQ to Kafka SHALL require a dependency swap and configuration change only — no application code change. |

---

## 6. Constraints

| ID | Constraint | Rationale |
|---|---|---|
| `CON-01` | Spring Boot **4.0.8**, Spring Cloud **2025.1.3 (Oakwood)**. | Oakwood is the current GA train and the first to target Boot 4.x. 4.0.x is its primary baseline; 4.1.x is supported from 2025.1.2 onward. An upgrade path to 4.1.x must be documented. |
| `CON-02` | Java **21** (LTS), Maven **3.9.x**, multi-module Maven reactor. | Matches the installed toolchain and the sibling project. |
| `CON-03` | Refreshable `@ConfigurationProperties` classes MUST use **setter-based JavaBean binding**, not constructor binding or records. | Spring Cloud's rebinder re-initialises the *existing* bean instance via setters. Constructor-bound and record-based properties are re-created, so previously injected references keep stale values — a long-standing and confirmed limitation. Immutability is preserved instead at the snapshot boundary (`FR-22`). |
| `CON-04` | The backend store is the single write path for configuration; the Config Server holds read-only credentials and exposes no mutation API. | Auditability and reproducibility. Per backend: `git commit` (A), SQL `INSERT`/`UPDATE` (B), `s3:PutObject` (C). |
| `CON-05` | GraalVM native image is not a target. | `@RefreshScope` and refresh are unsupported in native images. |
| `CON-06` | No `bootstrap.yml` / legacy bootstrap context. | Config-data import (`spring.config.import`) is the supported mechanism; legacy bootstrap is deprecated and excludes AOT. |

---

## 7. Acceptance criteria

Each criterion is a demonstrable, scripted test.

| ID | Given | When | Then | Verifies |
|---|---|---|---|---|
| `AC-01` | Config Server up, both clients up, both reporting a snapshot | An operator changes a shared property in `application.yml` and commits | Both clients report the new value within 2 s, without restart | `FR-02`, `FR-10`, `FR-12`, `NFR-01` |
| `AC-02` | Both clients up | An operator changes a property in `inventory-service.yml` only and commits | Only `inventory-service` refreshes; `pricing-service` snapshot version is unchanged | `FR-07`, `FR-25` |
| `AC-03` | `pricing-service` running as two instances on different ports | A property owned by `pricing-service` is committed | Both instances report the new value | `FR-12` |
| `AC-04` | A feature flag is `false` and the gated endpoint returns the disabled behaviour | The flag is committed as `true` | The very next request returns the enabled behaviour, with no restart | `FR-24` |
| `AC-05` | A client serving a valid snapshot | A property is committed with a value that violates its validation constraint | The client keeps the previous snapshot, continues serving, and reports the failure in health and metrics | `FR-30`, `NFR-20` |
| `AC-06` | Broker container stopped | A configuration change is committed, then the broker is restarted | Clients keep serving old config while the broker is down, health shows the degraded condition, and clients reconnect; a manual broadcast then applies the change | `FR-13`, `NFR-15` |
| `AC-07` | Config Server not started | A client is started | The client retries with backoff, then exits with an actionable error rather than starting unconfigured | `NFR-14` |
| `AC-08` | A secret stored in Git as a `{cipher}` value | A client fetches its configuration | The client receives the decrypted value; `grep` of the repository finds no plaintext secret; `/actuator/env` shows it masked | `FR-04`, `NFR-11`, `NFR-12` |
| `AC-09` | Any refresh occurs | The audit log is inspected | It records timestamp, trigger source, correlation id, changed key **names**, and outcome — and contains no property values | `FR-31`, `NFR-22` |
| `AC-10` | A load generator holds sustained concurrent traffic against a client | A configuration change is applied mid-load | Zero failed requests; every response is internally consistent with a single snapshot | `NFR-02`, `FR-32` |
| `AC-11` | The build is run clean | `mvn verify` executes | Format, static analysis, architecture rules, and coverage gates all pass; the broadcast integration test runs against a real broker | `NFR-30`, `NFR-31`, `NFR-32` |
| `AC-12` | The configuration repository URI is repointed to a GitHub remote | The Config Server is restarted with new env vars only | It serves configuration identically, with no rebuild | `FR-05`, `NFR-41` |

---

## 8. Risks

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| `R-01` | Constructor-bound / record configuration properties silently fail to refresh. | High — the core feature appears broken intermittently. | `CON-03` mandates setter binding; an ArchUnit rule fails the build if a `@ConfigurationProperties` class in a refreshable package is a record or lacks setters; `AC-01` proves the behaviour. |
| `R-02` | `@Value`-injected fields in plain singletons never update, misleading a reader into thinking refresh is unreliable. | Medium | Ban `@Value` for refreshable keys by architecture rule; all refreshable access goes through the snapshot provider (`FR-22`). |
| `R-03` | `file://` Git repository does not scale and emits no webhooks. | Medium | Accepted for this phase (`A-2`); bridged by the `post-commit` hook (`FR-16`); `NFR-13`/`AC-12` define the remote path. |
| `R-04` | Beans holding heavyweight resources (pools, clients) are re-created on refresh, causing latency spikes or leaks. | Medium | Only narrowly scoped beans are refresh-scoped; heavyweight beans are explicitly excluded and documented; `AC-10` measures request impact. |
| `R-05` | Broker becomes a single point of failure for propagation. | Medium | Propagation degrades, serving does not (`NFR-15`); manual broadcast fallback (`FR-13`). |
| `R-06` | Broadcast storm if a large commit maps to many applications. | Low | Path-scoped broadcast (`FR-07`); no-op refresh short-circuit (`FR-15`). |
| `R-07` | Boot 4.1.x / Oakwood combination has thinner documentation than 4.0.x. | Low | `CON-01` pins 4.0.8 and requires a documented upgrade path. |

---

## 9. Open items for sign-off

| ID | Question | Default if not answered |
|---|---|---|
| `Q-01` | Should the two client services model a real domain (inventory / pricing), or stay deliberately generic? | Named `inventory-service` and `pricing-service` with minimal but realistic behaviour. |
| `Q-02` | Is a shared internal library module for refresh plumbing wanted, or should each client be standalone and self-contained? | Shared module, to avoid duplicating the refresh/audit/snapshot machinery twice. |
| `Q-03` | Target for the coverage gate. | 80% line, 70% branch. |

---

## 10. Backend variants B and C

Requirements `FR-40`–`FR-49`, `NFR-50`–`NFR-54` and acceptance criteria `AC-13`–`AC-23` cover the
PostgreSQL/JDBC and AWS S3 backends. They are specified together with their design in
[SPECIFICATION-BACKENDS.md §9](SPECIFICATION-BACKENDS.md), because the requirements and the
mechanism are inseparable there: a database has no native change notification, so *how* a change
is detected is itself the requirement.

Approved decisions for those versions: one config-server with three Spring profiles ·
PostgreSQL 15+ · `LISTEN/NOTIFY` with a revision-poller reconciler · LocalStack with S3→SQS
notifications.

The governing constraint is `FR-41`: **client applications must remain byte-identical across all
three backends.** If satisfying a backend requires touching a client, the abstraction is wrong.
