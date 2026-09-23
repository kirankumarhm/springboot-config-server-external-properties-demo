# Technical Specification — Backend Variants B (PostgreSQL/JDBC) and C (AWS S3)

Addendum to [SPECIFICATION.md](SPECIFICATION.md) and [REQUIREMENTS.md](REQUIREMENTS.md).
Version A (Git) is specified in the base documents; this document specifies the two
additional backends and everything they change.

| Field | Value |
|---|---|
| Spec version | 1.0 (draft for approval) |
| Date | 2026-08-30 |
| Status | **Implemented. Version B in [version-b-jdbc/](version-b-jdbc/) — 27/27 checks passing. Version C in [version-c-s3/](version-c-s3/) — 26/26 checks passing.** Corrections from the running system are marked **[CORRECTED]**; full list in [README.md](README.md#findings-that-contradict-the-original-specification). |
| Approved decisions | One config-server, three Spring profiles · PostgreSQL · `LISTEN/NOTIFY` + revision poller · LocalStack with S3→SQS |

---

## 1. The central insight

**Swapping the storage backend is nearly free. Replacing the change trigger is the actual work.**

Spring Cloud Config already abstracts storage behind `EnvironmentRepository`, so moving from
Git to a database or a bucket is a profile switch plus a dependency. What does *not* move is the
mechanism that tells the Config Server something changed:

| | Version A — Git | Version B — PostgreSQL | Version C — AWS S3 |
|---|---|---|---|
| Storage abstraction | `MultipleJGitEnvironmentRepository` | `JdbcEnvironmentRepository` | `AwsS3EnvironmentRepository` |
| Native change notification | **Yes** — provider webhooks → `/monitor` | **None.** A database does not call you. | **Yes** — S3 Event Notifications → SNS/SQS/EventBridge |
| Trigger we must build | none (use `spring-cloud-config-monitor`) | Postgres trigger → `pg_notify` → in-process listener | SQS consumer in the Config Server |
| Change granularity | *Guessed* from the filename | **Exact** — `APPLICATION` is a column | Derived from the object key (which we control) |
| Change history / audit | `git log` | `PROPERTIES_HISTORY` table + trigger | S3 bucket versioning (`ListObjectVersions`) |
| Rollback | `git revert` | History table replay | Copy a prior object version over current |

Everything downstream of the trigger — the Bus, the clients, the snapshot pattern, the audit and
metrics — is **unchanged across all three versions**. That is the payoff of the profile-based
packaging (§8).

A secondary but real finding: the Git path is the *least* precise of the three. `PropertyPathEndpoint`
cannot know where an application name ends and a profile suffix begins, so it strips dash-separated
segments and broadcasts to every candidate. A commit to `inventory-service-dev.yml` publishes
**three** events — `inventory-service-dev`, `inventory-service`, and `inventory` — bounded only by
`spring.cloud.config.server.monitor.max-dashes`. Versions B and C carry an unambiguous application
identity, so both broadcast exactly once. This directly reduces risk `R-06` (broadcast storm).

---

## 2. Version matrix additions

Verified against Maven Central on 2026-08-30. Base matrix (Boot 4.0.8, Cloud 2025.1.3) is unchanged.

| Component | Version | Applies to | Note |
|---|---|---|---|
| `spring-boot-starter-data-jdbc` | managed by Boot | B | Required for `JdbcEnvironmentRepository`. |
| `org.postgresql:postgresql` | managed by Boot | B | |
| `flyway-core` + `flyway-database-postgresql` | managed by Boot | B | Schema is versioned code, not a README snippet. |
| `software.amazon.awssdk:s3` | managed by AWS BOM | C | Required dependency for `AwsS3EnvironmentRepository`. |
| `io.awspring.cloud:spring-cloud-aws-dependencies` | **4.1.1** | C | BOM. Built on `spring-cloud-build` 5.0.3 — the same build parent as Cloud 2025.1.3, which pins `spring-boot.version` **4.0.8**. Independent confirmation that the whole stack lines up. |
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | 4.1.1 | C | `@SqsListener` consumer. |
| `org.testcontainers:localstack` | 1.21.4 | C | Integration tests. |
| `org.testcontainers:postgresql` | 1.21.4 | B | Integration tests. |
| PostgreSQL server | **15+** | B | 15 is a hard floor — see the `NULLS NOT DISTINCT` constraint in §4.3. |
| LocalStack | `localstack/localstack:latest` (`SERVICES=s3,sqs`) | C | |

### 2.1 BOM ordering

```xml
<dependencyManagement>
  <dependencies>
    <!-- Spring Cloud FIRST: it wins for any artifact both BOMs manage. -->
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>2025.1.3</version><type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>io.awspring.cloud</groupId>
      <artifactId>spring-cloud-aws-dependencies</artifactId>
      <version>4.1.1</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
Never pin `software.amazon.awssdk:*` versions by hand — the AWS BOM manages the entire SDK as a
consistent set, and overriding one artifact is a reliable way to get `NoSuchMethodError` at runtime.

---

## 3. Unified change-detection SPI

New package in `config-server`: `com.example.config.server.change`.

```java
/** What changed, normalised across every backend. */
public record ConfigChangeNotification(
        Set<String> applications,   // "*" means all applications
        String      source,         // "git-webhook" | "pg-notify" | "jdbc-poll" | "s3-sqs"
        String      correlationId,
        Instant     detectedAt) {}

/** Detects changes in one specific backend. One implementation is active per profile. */
public interface ConfigChangeDetector extends SmartLifecycle {
    String source();
    HealthContribution health();
}

/** Single fan-out point onto the Bus. Exactly one implementation. */
public interface ConfigChangePublisher {
    void publish(ConfigChangeNotification notification);
}
```

```java
@Component
class BusConfigChangePublisher implements ConfigChangePublisher {

    private final ApplicationEventPublisher events;
    private final String busId;                    // spring.cloud.bus.id
    private final ConfigChangeMetrics metrics;

    @Override
    public void publish(ConfigChangeNotification n) {
        for (String destination : n.applications()) {
            // Mirrors PropertyPathEndpoint: a bare application name (or "*")
            // is matched against every instance id "app:index:id" by the Bus.
            events.publishEvent(new RefreshRemoteApplicationEvent(this, busId, destination));
            metrics.recordBroadcast(n.source(), destination);
        }
        auditor.recordBroadcast(n);
    }
}
```

Three detectors, each `@Profile`-gated:

| Profile | Detector | Mechanism |
|---|---|---|
| `git` | *(none — `spring-cloud-config-monitor` provides `PropertyPathEndpoint`)* | HTTP webhook |
| `jdbc` | `PostgresNotifyChangeDetector` | `LISTEN config_changed` on a dedicated connection |
| `jdbc` | `JdbcRevisionPollingDetector` | `CONFIG_REVISION` poll — always on, as reconciliation |
| `awss3` | `S3EventSqsChangeDetector` | `@SqsListener` on the notification queue |

**Design note — why B and C publish in-process rather than POSTing to `/monitor`:** the detectors
run *inside* the Config Server. Routing through `/monitor` would add an HTTP hop, require the
server to authenticate to itself, and drag in the webhook validation filter — which rejects
requests when no provider secret is configured. Publishing `RefreshRemoteApplicationEvent`
directly is the same event `PropertyPathEndpoint` publishes, minus all of that. It also
sidesteps the open question in [SPECIFICATION.md §5.4](SPECIFICATION.md) entirely for these two
versions.

---

## 4. Version B — PostgreSQL / JDBC backend

### 4.1 Activation

```yaml
spring:
  profiles:
    active: jdbc          # activates JdbcEnvironmentRepository AND our application-jdbc.yml
```
The `jdbc` profile name is load-bearing: Spring Cloud Config keys the backend
autoconfiguration off it. Setting the same name for our own profile-specific file is deliberate,
not a coincidence.

> The Config Server's own `spring.profiles.active` is unrelated to the profiles it *serves* to
> clients. A client asking for profile `dev` is unaffected by the server running under `jdbc`.

### 4.2 Resolution semantics (read from `JdbcEnvironmentRepository` source)

For a client request `(application, profile, label)`, the repository issues **four** queries per
label and builds the property-source order below. First match wins.

| Precedence | `APPLICATION` | `PROFILE` | Equivalent Git file |
|---|---|---|---|
| 1 (highest) | `<app>` | `<profile>` | `inventory-service-dev.yml` |
| 2 | `application` | `<profile>` | `application-dev.yml` |
| 3 | `<app>` | **`NULL`** | `inventory-service.yml` |
| 4 (lowest) | `application` | **`NULL`** | `application.yml` |

Three consequences that drive the schema and seed data:

1. **Shared properties live in rows with `APPLICATION = 'application'`** — the literal string.
   The repository unconditionally prepends `"application,"` to the requested application name.
2. **Profile-independent rows must have `PROFILE IS NULL`** — not `'default'`, not `''`. The
   default query for these rows is literally `... and PROFILE is null and LABEL=?`.
3. **`LABEL` is never null.** A blank request label is replaced by `defaultLabel`, which
   **defaults to `master`** — not `main`. §4.4 sets it explicitly.

The exact default statements:
```sql
-- spring.cloud.config.server.jdbc.sql
SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE=? and LABEL=?
-- spring.cloud.config.server.jdbc.sql-without-profile
SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE is null and LABEL=?
```

### 4.3 Schema (Flyway `V1__config_schema.sql`)

```sql
CREATE TABLE properties (
    id          BIGSERIAL     PRIMARY KEY,
    application VARCHAR(128)  NOT NULL,
    profile     VARCHAR(128)  NULL,          -- NULL = profile-independent (§4.2)
    label       VARCHAR(128)  NOT NULL DEFAULT 'main',
    "key"       VARCHAR(512)  NOT NULL,
    "value"     TEXT          NULL,          -- may hold a {cipher} value
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by  VARCHAR(128)  NOT NULL DEFAULT current_user,
    -- Postgres 15+. Without NULLS NOT DISTINCT, every profile-independent row
    -- (profile IS NULL) escapes the uniqueness check, because NULLs are
    -- distinct in a default UNIQUE constraint. That silently permits duplicate
    -- keys whose winner depends on physical row order.
    CONSTRAINT uq_properties
        UNIQUE NULLS NOT DISTINCT (application, profile, label, "key")
);

CREATE INDEX idx_properties_lookup ON properties (application, profile, label);

-- Replaces `git log`: full who/what/when for every mutation.
CREATE TABLE properties_history (
    history_id  BIGSERIAL    PRIMARY KEY,
    operation   CHAR(1)      NOT NULL,       -- I | U | D
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by  VARCHAR(128) NOT NULL DEFAULT current_user,
    application VARCHAR(128) NOT NULL,
    profile     VARCHAR(128) NULL,
    label       VARCHAR(128) NOT NULL,
    "key"       VARCHAR(512) NOT NULL,
    old_value   TEXT         NULL,
    new_value   TEXT         NULL
);

-- Monotonic per-application revision. Powers the polling reconciler (§4.6).
CREATE TABLE config_revision (
    application VARCHAR(128) PRIMARY KEY,
    revision    BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

`"key"` and `"value"` are quoted because both are reserved words; the shipped default SQL quotes
them the same way. This is exactly why PostgreSQL was chosen — see the portability note in §4.7.

Adding `id`, audit and timestamp columns is safe: the default statements select named columns
(`SELECT "KEY", "VALUE"`), never `SELECT *`.

### 4.4 Server configuration (`application-jdbc.yml`)

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/configdb}
    username: ${DB_USERNAME:config_server}
    password: ${DB_PASSWORD}                 # no default — fail loudly
    hikari:
      maximum-pool-size: 10
      pool-name: config-read-pool
  flyway:
    enabled: true
    locations: classpath:db/migration
  cloud:
    config:
      server:
        jdbc:
          enabled: true
          default-label: ${CONFIG_LABEL:main}   # shipped default is 'master' (§4.2)
          fail-on-error: true                    # NFR-14/NFR-16: never serve a silent empty env
          order: 1
          # Do NOT override `sql` alone — see §4.7.

app:
  config-change:
    jdbc:
      notify-channel: config_changed
      listener:
        enabled: true
        reconnect-initial-backoff: 1s
        reconnect-max-backoff: 30s
        poll-timeout: 10s
      revision-poller:
        enabled: true
        interval: 15s
```

Read-only DB grant for the Config Server principal:
```sql
GRANT SELECT ON properties, config_revision TO config_server;
-- No INSERT/UPDATE/DELETE. The server never writes configuration (CON-04 preserved).
```

### 4.5 Change detection — trigger and `LISTEN/NOTIFY`

`V2__config_change_notify.sql`:

```sql
CREATE FUNCTION fn_properties_history() RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        INSERT INTO properties_history(operation, application, profile, label, "key", old_value)
        VALUES ('D', OLD.application, OLD.profile, OLD.label, OLD."key", OLD."value");
        RETURN OLD;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO properties_history(operation, application, profile, label, "key", old_value, new_value)
        VALUES ('U', NEW.application, NEW.profile, NEW.label, NEW."key", OLD."value", NEW."value");
        RETURN NEW;
    ELSE
        INSERT INTO properties_history(operation, application, profile, label, "key", new_value)
        VALUES ('I', NEW.application, NEW.profile, NEW.label, NEW."key", NEW."value");
        RETURN NEW;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_properties_history
    AFTER INSERT OR UPDATE OR DELETE ON properties
    FOR EACH ROW EXECUTE FUNCTION fn_properties_history();

-- Statement-level with transition tables (Postgres 10+): a bulk UPDATE touching
-- 500 rows of one application produces ONE notification, not 500. Directly
-- mitigates R-06 without any debounce logic in the application.
CREATE FUNCTION fn_notify_config_change() RETURNS trigger AS $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT DISTINCT application FROM changed_rows
    LOOP
        INSERT INTO config_revision(application, revision, updated_at)
             VALUES (rec.application, 1, now())
        ON CONFLICT (application)
             DO UPDATE SET revision = config_revision.revision + 1, updated_at = now();

        -- NOTIFY is transactional: the payload is delivered only if this
        -- transaction COMMITS. A rolled-back change never broadcasts.
        -- This gives transactional-outbox semantics with no outbox table.
        PERFORM pg_notify('config_changed', json_build_object(
            'application',   rec.application,
            'correlationId', md5(random()::text || clock_timestamp()::text),
            'detectedAt',    to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MSOF')
        )::text);
    END LOOP;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notify_config_insert AFTER INSERT ON properties
    REFERENCING NEW TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();
CREATE TRIGGER trg_notify_config_update AFTER UPDATE ON properties
    REFERENCING NEW TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();
CREATE TRIGGER trg_notify_config_delete AFTER DELETE ON properties
    REFERENCING OLD TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();
```

Two properties of this design worth stating plainly:

- **Transactional delivery for free.** `NOTIFY` is queued and delivered at commit. This is the
  guarantee the "admin API + transactional outbox" alternative has to build by hand, and it
  applies to *every* writer, not just the sanctioned one.
- **Any writer is covered.** A DBA running `psql` by hand, a Liquibase migration, or a future
  admin UI all trigger propagation. Nothing can change configuration invisibly.

Listener (`PostgresNotifyChangeDetector`):

```java
@Component
@Profile("jdbc")
class PostgresNotifyChangeDetector implements ConfigChangeDetector {

    // A DEDICATED connection, deliberately outside the Hikari read pool.
    // LISTEN binds to a session for its whole lifetime; borrowing a pooled
    // connection and never returning it would starve the pool and, worse,
    // silently lose the subscription if the pool recycled it.
    private final DataSource notificationDataSource;   // maximumPoolSize=1
    private final ConfigChangePublisher publisher;
    private final TaskExecutor executor;
    private volatile boolean connected;

    @Override public void start() { executor.execute(this::listenLoop); }

    private void listenLoop() {
        Backoff backoff = Backoff.exponential(initialBackoff, maxBackoff);
        while (running) {
            try (Connection c = notificationDataSource.getConnection()) {
                try (Statement s = c.createStatement()) { s.execute("LISTEN config_changed"); }
                connected = true;
                backoff.reset();
                revisionPoller.reconcileNow();   // catch anything missed while disconnected
                PGConnection pg = c.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications((int) pollTimeout.toMillis());
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            publisher.publish(parse(n.getParameter()));
                        }
                    }
                }
            } catch (SQLException ex) {
                connected = false;
                metrics.recordListenerDrop(ex);
                backoff.sleep();                 // then reconnect
            }
        }
    }
}
```

`getNotifications(timeout)` blocks up to the timeout, which doubles as the connection liveness
check — a dead socket surfaces as an exception rather than a silently wedged thread.

### 4.6 Revision poller — reconciliation, not redundancy

`JdbcRevisionPollingDetector` is **always enabled**, even when the listener is healthy. It is
cheap (one indexed query per interval) and it is the only thing that closes these gaps:

| Gap | Why the listener alone is insufficient |
|---|---|
| Listener disconnected during a commit | `NOTIFY` payloads are delivered to *current* listeners only. A change committed while the socket was down is lost forever. |
| Server restarted | Same reason. |
| `NOTIFY` queue overflow | Postgres has a bounded async notification queue; under extreme write volume, notifications can be dropped. |

It keeps an in-memory `Map<String, Long>` of last-seen revisions, publishes on any drift, and is
also invoked explicitly on listener reconnect (see `reconcileNow()` above). Net effect: the
`NFR-01` SLA is met via `NOTIFY` (~sub-second), and the *worst case* degrades to the poll
interval instead of to "never".

### 4.7 Gotchas

| # | Gotcha | Handling |
|---|---|---|
| 1 | `default-label` ships as **`master`**, not `main`. Wrong label ⇒ every lookup returns empty and the client starts with no properties. | Set `default-label` explicitly; Flyway seeds `label = 'main'`; a startup smoke check asserts a known key resolves. |
| 2 | Overriding `sql` **without** also overriding `sql-without-profile` sets `configIncomplete = true`, which **skips the null-profile queries entirely** and instead prepends `default,` to the profile list. Resolution semantics change silently. | Override both or neither. An `ApplicationRunner` assertion fails startup if exactly one is customised. |
| 3 | Default `UNIQUE` treats NULLs as distinct, so profile-independent rows can be duplicated. | `UNIQUE NULLS NOT DISTINCT` — hence PostgreSQL 15+ as a floor. |
| 4 | **[CORRECTED] This had it backwards.** The defaults select `"KEY"`/`"VALUE"` in **UPPERCASE**, and PostgreSQL double quotes are case-**sensitive**, so the shipped SQL does not match lowercase `key`/`value` columns either — it fails with `column "KEY" does not exist`. PostgreSQL is *not* natively compatible. | Either declare the columns as uppercase `"KEY"`/`"VALUE"`, or override **both** `sql` and `sql-without-profile`. The implementation overrides both and keeps idiomatic lowercase columns, because operators edit configuration with hand-written SQL in this version. |
| 5 | Profile-independent rows written as `PROFILE = 'default'` or `''` are never found. | `CHECK (profile <> '')`; Flyway seed uses `NULL`; a repository test asserts the four-way precedence of §4.2. |
| 6 | `fail-on-error: false` would make a DB outage look like "application has no properties" — far worse than a hard failure. | Kept `true` (`NFR-16`). |
| 7 | Bulk writes could produce one broadcast per row. | Statement-level triggers with transition tables (§4.5). |
| 8 | `LISTEN` on a pooled connection starves or silently loses the subscription. | Dedicated single-connection `DataSource` (§4.5). |

**Portability note (MySQL / Oracle).** Only two properties change — no code:
```yaml
spring.cloud.config.server.jdbc:
  sql: "SELECT `KEY`, `VALUE` FROM PROPERTIES WHERE APPLICATION=? AND PROFILE=? AND LABEL=?"
  sql-without-profile: "SELECT `KEY`, `VALUE` FROM PROPERTIES WHERE APPLICATION=? AND PROFILE IS NULL AND LABEL=?"
```
Change detection then loses `pg_notify` and falls back to the revision poller, plus optionally
Oracle `DBMS_AQ` / MySQL binlog CDC (Debezium) as an alternate `ConfigChangeDetector`. The SPI in
§3 is the seam that makes this a new class rather than a rewrite.

---

## 5. Version C — AWS S3 backend

### 5.1 Activation and dependencies

```yaml
spring:
  profiles:
    active: awss3
```

| Artifact | Why |
|---|---|
| `software.amazon.awssdk:s3` | Required by `AwsS3EnvironmentRepository`; presence of this artifact plus the `awss3` profile is what wires the backend. |
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | `@SqsListener` for the notification queue. |

### 5.2 Bucket layout

Standard layout (`use-directory-layout: false`) is chosen because object keys are then
**byte-identical to the Git version's filenames**, so the same configuration content is portable
across all three versions and the acceptance scripts need no per-version branching.

```
s3://acme-platform-config/
├── main/                                 ← label as key prefix
│   ├── application.yml                   → all applications
│   ├── inventory-service.yml
│   ├── inventory-service-dev.yml
│   ├── pricing-service.yml
│   └── pricing-service-dev.yml
└── release-2026.09/                      ← a second label = a config release candidate
    └── ...
```

Supported formats: `.properties`, `.yml`, `.yaml`, `.json`. Label maps to a directory prefix;
`use-directory-layout: true` would instead give `/{application}/application-{profile}.yml`.

**Bucket versioning is mandatory for this design** — it is what replaces `git log`:

| Git capability | S3 equivalent |
|---|---|
| `git log <file>` | `ListObjectVersions` on the key |
| `git show <sha>:<file>` | `GetObject` with `versionId` |
| `git revert` | `CopyObject` from an old `versionId` onto the current key (which itself creates a new version — the rollback is auditable too) |
| `git tag` | Key prefix (label) or an object tag |

A lifecycle rule (`NoncurrentVersionExpiration: 365 days`) bounds retention and cost.

### 5.3 Notification pipeline

```
   operator: aws s3 cp inventory-service.yml s3://acme-platform-config/main/
        │
        ▼  s3:ObjectCreated:*  /  s3:ObjectRemoved:*   (prefix filter: main/)
   ┌──────────────────────────────┐
   │ S3 Event Notification        │
   └───────────┬──────────────────┘
               ▼
   ┌──────────────────────────────┐        ┌───────────────────────────┐
   │ SQS  config-change-queue     │───────▶│ config-change-dlq         │
   │ visibility 30s               │ redrive│ maxReceiveCount 3         │
   └───────────┬──────────────────┘        └───────────────────────────┘
               ▼ @SqsListener
   ┌──────────────────────────────────────────────────────────┐
   │ S3EventSqsChangeDetector                                 │
   │  1. parse S3 event records                               │
   │  2. S3ObjectKeyApplicationMapper: key → application name │
   │  3. de-duplicate within the batch                        │
   │  4. ConfigChangePublisher.publish(...)                   │
   └───────────┬──────────────────────────────────────────────┘
               ▼  RefreshRemoteApplicationEvent → RabbitMQ Bus → clients
```

Key→application mapping (`S3ObjectKeyApplicationMapper`, shared with the JDBC and Git paths):

| Object key | Application destination |
|---|---|
| `main/inventory-service.yml` | `inventory-service` |
| `main/inventory-service-dev.yml` | `inventory-service` |
| `main/application.yml` | `*` (all) |
| `main/pricing-service.json` | `pricing-service` |

Unlike `PropertyPathEndpoint`'s dash-guessing, this mapper resolves the profile suffix against
the **known set of application names** (from `app.known-applications`, itself refreshable), so
`inventory-service-dev.yml` maps to exactly one destination.

```java
@Component
@Profile("awss3")
class S3EventSqsChangeDetector implements ConfigChangeDetector {

    @SqsListener(value = "${app.config-change.s3.queue-name}",
                 acknowledgementMode = SqsListenerAcknowledgementMode.ON_SUCCESS)
    void onBucketEvent(S3EventNotification event, @Header("MessageId") String messageId) {
        Set<String> apps = event.getRecords().stream()
                .map(r -> URLDecoder.decode(r.getS3().getObject().getKey(), UTF_8))
                .filter(keyFilter::isConfigObject)      // ignore stray non-config keys
                .map(mapper::toApplication)
                .flatMap(Optional::stream)
                .collect(toCollection(LinkedHashSet::new));   // de-dup within batch

        if (!apps.isEmpty()) {
            publisher.publish(new ConfigChangeNotification(apps, "s3-sqs", messageId, Instant.now()));
        }
        // ON_SUCCESS: the message is deleted only if we return normally. A throw
        // returns it to the queue and, after 3 attempts, lands it in the DLQ.
    }
}
```

### 5.4 Server configuration (`application-awss3.yml`)

```yaml
spring:
  cloud:
    aws:
      region.static: ${AWS_REGION:us-east-1}
      # endpoint + static credentials are LocalStack-only; on real AWS both are
      # omitted so the Default Credential Provider Chain / IRSA takes over.
      endpoint: ${AWS_ENDPOINT:}
      credentials:
        access-key: ${AWS_ACCESS_KEY_ID:}
        secret-key: ${AWS_SECRET_ACCESS_KEY:}
      sqs.endpoint: ${AWS_ENDPOINT:}
    config:
      server:
        awss3:
          region: ${AWS_REGION:us-east-1}
          bucket: ${CONFIG_BUCKET:acme-platform-config}
          endpoint: ${AWS_ENDPOINT:}          # empty on real AWS
          use-directory-layout: false
          order: 1

app:
  config-change:
    s3:
      queue-name: ${CONFIG_CHANGE_QUEUE:config-change-queue}
      key-prefix: main/
```

### 5.5 Security and IAM

Least-privilege policy for the Config Server. Note the deliberate absence of any write action —
the server reads configuration and never publishes it (`CON-04`).

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:GetObjectVersion"],
      "Resource": "arn:aws:s3:::acme-platform-config/*" },
    { "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:ListBucketVersions"],
      "Resource": "arn:aws:s3:::acme-platform-config" },
    { "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"],
      "Resource": "arn:aws:sqs:*:*:config-change-queue" }
  ]
}
```

Bucket hardening: Block Public Access on all four settings; SSE-KMS default encryption; a bucket
policy denying `aws:SecureTransport = false`. **`{cipher}` values are still used for secrets**
even inside an encrypted bucket — SSE protects against storage-layer compromise, `{cipher}`
protects against anyone with read access to the bucket. Defence in depth; `NFR-11` is unchanged
across all three versions.

> **[CORRECTED]** No configuration value is `{cipher}`-encrypted any more. The single encrypted
> secret was removed from all three stores because it coupled client startup to the Config
> Server's keystore: a ciphertext the keystore cannot decrypt is served back as
> `invalid.<key>`, which the client then rejects with `@NotBlank` and fails to start. SSE-KMS on
> the bucket is unaffected, and the `/encrypt` workflow remains available and authenticated.

Credentials in production come from IRSA / instance profile, never static keys. The static-key
properties above exist solely so LocalStack works with its `test`/`test` dummies.

### 5.6 Local runtime — LocalStack

`docker/compose.yaml` gains:

```yaml
  localstack:
    image: localstack/localstack:latest
    ports: ["4566:4566"]
    environment:
      SERVICES: s3,sqs
      DEBUG: "0"
    volumes:
      - ./localstack-init:/etc/localstack/init/ready.d      # runs once when ready
```

`docker/localstack-init/01-provision.sh` — bucket, versioning, DLQ, redrive, and the S3→SQS
notification wiring:

```sh
#!/bin/sh
set -e
B=acme-platform-config; Q=config-change-queue; DLQ=config-change-dlq
awslocal s3 mb "s3://$B"
awslocal s3api put-bucket-versioning --bucket "$B" \
  --versioning-configuration Status=Enabled
awslocal sqs create-queue --queue-name "$DLQ"
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$(awslocal sqs get-queue-url --queue-name "$DLQ" --query QueueUrl --output text)" \
          --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name "$Q" --attributes "{
  \"VisibilityTimeout\":\"30\",
  \"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
Q_ARN=$(awslocal sqs get-queue-attributes --queue-url "$(awslocal sqs get-queue-url --queue-name "$Q" --query QueueUrl --output text)" \
        --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal s3api put-bucket-notification-configuration --bucket "$B" \
  --notification-configuration "{\"QueueConfigurations\":[{
      \"QueueArn\":\"$Q_ARN\",
      \"Events\":[\"s3:ObjectCreated:*\",\"s3:ObjectRemoved:*\"],
      \"Filter\":{\"Key\":{\"FilterRules\":[{\"Name\":\"prefix\",\"Value\":\"main/\"}]}}}]}"
awslocal s3 sync /etc/localstack/init/seed "s3://$B/main/"
```

Because the *same* AWS APIs are used locally and in production, the deployment delta is only:
drop `AWS_ENDPOINT` and the static keys, and attach the IAM role.

### 5.7 Gotchas

| # | Gotcha | Handling |
|---|---|---|
| 1 | **S3 event delivery is at-least-once and unordered.** The same change can trigger several refreshes. | Refresh is idempotent by construction: a no-op refresh does not bump the snapshot version (`FR-15`), and the provider swap in [SPECIFICATION.md §6.3](SPECIFICATION.md) is atomic. Batch-level de-duplication trims the obvious cases. |
| 2 | Object keys arrive **URL-encoded** in S3 events (`+` for space, `%3D` for `=`). Naive parsing corrupts the key and the mapping silently misses. | Explicit `URLDecoder.decode(key, UTF_8)` before mapping. |
| 3 | **[CORRECTED] `T-S3-03` resolved: no path-style override needed.** The emulator used is **Floci**, not LocalStack, and its wildcard DNS resolves `<bucket>.localhost.floci.io` to 127.0.0.1, so virtual-host-style addressing works as-is. | From inside a container, two `extra_hosts` entries mapped to `host-gateway` (`localhost.floci.io` and `<bucket>.localhost.floci.io`) are sufficient. No custom `S3Client` bean was needed. |
| 3b | *(not anticipated)* | **The label is a key PREFIX.** The bucket's `main/` prefix *is* label `main`. `GET /{app}/{profile}` with no label reads the bucket root and correctly returns **zero** property sources — which looks like a broken server but is expected. Clients send `label: main`. |
| 4 | An operator uploading several files for one logical change produces several events and several broadcasts. | Prefix filter plus a short coalescing window (default 500 ms) in the detector, which collapses events per application before publishing. |
| 5 | A `.yml` with a syntax error is accepted by S3 (it is just bytes) and only fails when a client fetches. | Server-side validation on the fetch path plus the client's last-known-good guarantee (`FR-30`); optionally a CI job that parses every object before upload. |
| 6 | Deleting `application.yml` broadcasts `*` and could strip shared properties from every client at once. | `s3:ObjectRemoved:*` is handled, but clients retain last-known-good if the result fails validation; bucket versioning makes the delete instantly reversible. |
| 7 | Read-after-write: a fetch triggered by the event must see the new object. | S3 has been strongly read-after-write consistent for PUTs since December 2020, so no retry loop is needed. Worth stating because older guidance assumed otherwise. |
| 8 | SQS visibility timeout shorter than broadcast handling time ⇒ duplicate deliveries. | Visibility 30 s against a handler budget of well under 1 s; `ON_SUCCESS` acknowledgement. |

---

## 6. Composite mode — zero-downtime backend migration

`EnvironmentRepository` implementations are ordered, so more than one backend can serve
simultaneously. Lower `order` wins.

```yaml
spring:
  profiles:
    active: git,jdbc        # both repositories active
  cloud:
    config:
      server:
        jdbc.order: 1       # database wins
        git.order: 2        # git as the fallback / not-yet-migrated remainder
```

Shipped defaults differ (`JdbcEnvironmentProperties.order` is `DEFAULT_ORDER - 10`, i.e. higher
precedence than `AwsS3EnvironmentProperties.order` at `DEFAULT_ORDER`), so **always set `order`
explicitly in composite mode** rather than relying on defaults.

Migration runbook Git → PostgreSQL:
1. Activate `git,jdbc` with `git.order: 1` (Git still authoritative). Nothing changes for clients.
2. Import the Git content into `properties` and diff the served `/{app}/{profile}` output
   between the two repositories until identical.
3. Flip to `jdbc.order: 1`. Clients are unaffected — the Environment API response is unchanged.
4. Observe, then drop the `git` profile.

Caveat: in composite mode, every active repository must succeed or the request fails. Combined
with `fail-on-error: true`, a database outage during step 1–3 fails requests rather than
silently falling back to Git. That is the correct trade (`NFR-16`) but must be a conscious one.

---

## 7. Backend comparison

| Criterion | A — Git | B — PostgreSQL | C — AWS S3 |
|---|---|---|---|
| Propagation latency (p95 target) | ≤ 2 s | ≤ 2 s (`NOTIFY`); ≤ 15 s worst case (poller) | ≤ 3 s (extra SQS hop) |
| Change granularity | Guessed, over-broadcasts | Exact (column) | Exact (key → known app) |
| Trigger reliability | Webhook delivery is provider-dependent | Transactional; delivered on commit only | At-least-once, unordered |
| Audit trail | Native, excellent | History table + trigger | Object versions |
| Review/approval workflow | **Native (pull requests)** | Needs an admin app or DBA process | Needs a CI pipeline in front of upload |
| Rollback | `git revert` | History replay | Copy prior version |
| Operational cost | Lowest | DB to run, patch, back up | Managed; near-zero ops |
| HA story | Needs `ssh:` + `force-pull` per replica | Native (DB is already HA) | Native (S3 is already HA) |
| Bulk/programmatic edits | Awkward | **Natural (SQL)** | Awkward (whole-object rewrite) |
| Secret handling | `{cipher}` (available, unused) | `{cipher}` (available, unused) | `{cipher}` (available, unused) + SSE-KMS |
| Best suited to | Team-reviewed config as code | Config edited by tooling/an admin UI, or already-DB-centric shops | AWS-native platforms, large config sets, minimal ops |

Recommendation, if one must be chosen for production: **Git** when configuration should be
code-reviewed, **PostgreSQL** when configuration is written by tooling rather than humans,
**S3** when the platform is AWS-native and ops headcount is the constraint.

---

## 8. What does *not* change

This is the measure of whether the abstraction is right. Across all three versions:

- **Both client services are byte-identical.** No new dependency, no property change, no code
  change. They talk to the Environment API and listen on the Bus; neither is backend-aware.
- Each client's own `refresh/` plumbing is untouched — snapshot provider, validation,
  last-known-good, audit, metrics, health. (**[CORRECTED]** this was originally a shared
  `config-client-commons` module; it is now duplicated per client, and the backend-independence
  claim holds either way.)
- The Bus, RabbitMQ, and every `RefreshRemoteApplicationEvent` destination string.
- Every client-side acceptance criterion (`AC-03`…`AC-07`, `AC-09`, `AC-10`) passes unmodified
  against any backend; the harness is parameterised by profile only.
- `{cipher}` encryption and the `/encrypt` workflow (encryption is applied by the server as a
  post-processing step after retrieval, independent of the repository).

The only backend-aware code in the whole system is one `ConfigChangeDetector` implementation
per profile — roughly 150 lines each, behind the §3 SPI.

---

## 9. Additional requirements

Extends [REQUIREMENTS.md §10](REQUIREMENTS.md).

| ID | Requirement | Priority |
|---|---|---|
| `FR-40` | The Config Server SHALL support Git, JDBC/PostgreSQL, and AWS S3 backends, selected by Spring profile with no code change and no separate artefact. | Must |
| `FR-41` | All three backends SHALL converge on one `ConfigChangePublisher`, so client applications remain byte-identical across backends. | Must |
| `FR-42` | The JDBC backend SHALL detect changes made by **any** writer, including out-of-band SQL, and SHALL NOT broadcast for a rolled-back transaction. | Must |
| `FR-43` | The JDBC backend SHALL provide a full change audit (who/what/when/old/new) as a replacement for `git log`. | Must |
| `FR-44` | JDBC change detection SHALL survive listener-connection loss without permanently missing a change. | Must |
| `FR-45` | The S3 backend SHALL detect changes via S3 Event Notifications consumed from SQS, with a dead-letter queue for poison messages. | Must |
| `FR-46` | The S3 bucket SHALL have versioning enabled, and version history SHALL be usable for audit and rollback. | Must |
| `FR-47` | S3 change handling SHALL be idempotent under at-least-once, unordered delivery. | Must |
| `FR-48` | Backends SHALL be runnable in composite mode with explicit ordering, to support zero-downtime migration between them. | Should |
| `FR-49` | Application-name resolution SHALL be exact for the JDBC and S3 backends (no dash-guessing over-broadcast). | Should |
| `NFR-50` | Propagation SLA: ≤ 2 s p95 for Git and JDBC; ≤ 3 s p95 for S3. JDBC worst case (listener down) ≤ the poll interval, default 15 s. |
| `NFR-51` | The Config Server SHALL hold **read-only** credentials on every backend: no DB write grants, no `s3:PutObject`, no Git push. |
| `NFR-52` | The DB schema SHALL be managed by versioned Flyway migrations, never by manual DDL or `ddl-auto`. |
| `NFR-53` | Local runtime for all three backends SHALL come up from one `docker compose up` (RabbitMQ, PostgreSQL, LocalStack). |
| `NFR-54` | Each backend's integration tests SHALL run against a real dependency via Testcontainers (PostgreSQL, LocalStack), not a mock. |

### Acceptance criteria

| ID | Given | When | Then | Verifies |
|---|---|---|---|---|
| `AC-13` | Server on `jdbc`, both clients up | `UPDATE properties SET "value"='750' WHERE application='inventory-service' AND "key"='inventory.max-order-quantity'` committed via `psql` | Only `inventory-service` refreshes, within 2 s, with no restart and no application involvement in the write | `FR-40`, `FR-42`, `FR-49` |
| `AC-14` | Server on `jdbc` | The same `UPDATE` inside a transaction that is **rolled back** | No broadcast; no client snapshot version changes | `FR-42` |
| `AC-15` | Server on `jdbc`, listener connection killed at the DB (`pg_terminate_backend`) | A change is committed while disconnected, then the listener reconnects | The reconciler detects the missed revision and broadcasts; health shows the drop and the recovery | `FR-44`, `NFR-50` |
| `AC-16` | Server on `jdbc` | A bulk `UPDATE` touching 500 rows of one application | Exactly **one** broadcast for that application | `FR-49`, `R-06` |
| `AC-17` | Server on `jdbc` | Any property mutation | `properties_history` holds operation, actor, timestamp, old and new value | `FR-43` |
| `AC-18` | Server on `awss3`, LocalStack provisioned | `aws s3 cp inventory-service.yml s3://…/main/` | `inventory-service` refreshes within 3 s; `pricing-service` untouched | `FR-45`, `FR-49`, `NFR-50` |
| `AC-19` | Server on `awss3` | The same S3 event is delivered three times | One snapshot version increment; no error; no duplicate audit entry | `FR-47` |
| `AC-20` | Server on `awss3` | A malformed event that always throws is delivered | After 3 attempts the message lands in the DLQ; the listener keeps processing subsequent messages | `FR-45` |
| `AC-21` | Server on `awss3`, versioning on | A property object is overwritten, then rolled back by copying the prior `versionId` | Clients refresh to the old value; both the change and the rollback appear in version history | `FR-46` |
| `AC-22` | Server on `git,jdbc` composite, `git.order=1` | The order is flipped to `jdbc.order=1` and the server restarted | The Environment API response is byte-identical before and after; clients are unaffected | `FR-48` |
| `AC-23` | The full client acceptance suite (`AC-03`…`AC-07`, `AC-09`, `AC-10`) | Run three times, once per backend profile | Identical results; **zero** changes to client code or configuration | `FR-41` |

---

## 10. Implementation plan additions

Extends [SPECIFICATION.md §12](SPECIFICATION.md). Version A (`T-01`…`T-14`) lands first; the SPI
refactor `T-15` then makes B and C additive.

| ID | Task | Proves |
|---|---|---|
| `T-15` | Extract the §3 SPI (`ConfigChangeNotification`, `ConfigChangeDetector`, `ConfigChangePublisher`, `BusConfigChangePublisher`, shared `ApplicationNameMapper`); retro-fit the Git path onto it | `FR-41` |
| `T-16` | Profile-partition the server config into `application-git.yml` / `-jdbc.yml` / `-awss3.yml` | `FR-40` |
| `T-DB-01` | PostgreSQL in compose; Flyway `V1` schema + seed mirroring `config-repo` content | `NFR-52`, `NFR-53` |
| `T-DB-02` | Enable the `jdbc` backend; assert the four-way precedence of §4.2 and the label/null-profile gotchas | `FR-40`, gotchas 1/2/5 |
| `T-DB-03` | Flyway `V2`: history trigger, revision table, statement-level notify trigger | `FR-43`, `AC-16`, `AC-17` |
| `T-DB-04` | `PostgresNotifyChangeDetector` with dedicated connection, backoff, health | `FR-42`, `AC-13`, `AC-14` |
| `T-DB-05` | `JdbcRevisionPollingDetector` + reconcile-on-reconnect | `FR-44`, `AC-15` |
| `T-DB-06` | Testcontainers PostgreSQL integration tests incl. rollback and connection-kill | `NFR-54`, `AC-13`–`AC-17` |
| `T-S3-01` | LocalStack in compose + provisioning script (bucket, versioning, queue, DLQ, notification) | `NFR-53`, `FR-46` |
| `T-S3-02` | Enable the `awss3` backend; verify standard-layout key resolution and label prefixes | `FR-40` |
| `T-S3-03` | **Resolve the path-style addressing question in §5.7 gotcha 3** | `FR-40` |
| `T-S3-04` | `S3EventSqsChangeDetector` + `S3ObjectKeyApplicationMapper` + coalescing window | `FR-45`, `FR-49`, `AC-18` |
| `T-S3-05` | Idempotency and DLQ behaviour | `FR-47`, `AC-19`, `AC-20` |
| `T-S3-06` | IAM policy, bucket hardening, `{cipher}` on top of SSE-KMS | `NFR-51` |
| `T-S3-07` | Testcontainers LocalStack integration tests incl. rollback-by-version | `NFR-54`, `AC-21` |
| `T-X-01` | Composite mode + the §6 migration runbook | `FR-48`, `AC-22` |
| `T-X-02` | Parameterise the acceptance harness by profile; run the client suite three times | `FR-41`, `AC-23` |
| `T-X-03` | README: backend comparison (§7) and per-backend runbooks | — |

---

## 11. Sources

- [Config Server — JDBC backend](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/jdbc-backend.html)
- [Config Server — AWS S3 backend](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/aws-s3-backend.html)
- Source of record for the semantics documented in §4.2 and §4.7: [`JdbcEnvironmentRepository`](https://github.com/spring-cloud/spring-cloud-config/blob/main/spring-cloud-config-server/src/main/java/org/springframework/cloud/config/server/environment/JdbcEnvironmentRepository.java), [`JdbcEnvironmentProperties`](https://github.com/spring-cloud/spring-cloud-config/blob/main/spring-cloud-config-server/src/main/java/org/springframework/cloud/config/server/environment/JdbcEnvironmentProperties.java), [`AwsS3EnvironmentProperties`](https://github.com/spring-cloud/spring-cloud-config/blob/main/spring-cloud-config-server/src/main/java/org/springframework/cloud/config/server/environment/AwsS3EnvironmentProperties.java)
- Dash-guessing / over-broadcast behaviour: [`PropertyPathEndpoint`](https://github.com/spring-cloud/spring-cloud-config/blob/main/spring-cloud-config-monitor/src/main/java/org/springframework/cloud/config/monitor/PropertyPathEndpoint.java)
- [Spring Cloud AWS](https://github.com/awspring/spring-cloud-aws) — version 4.1.1, built on `spring-cloud-build` 5.0.3 (`spring-boot.version` 4.0.8)
- [Amazon S3 Event Notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/NotificationHowTo.html)
- [PostgreSQL `NOTIFY`](https://www.postgresql.org/docs/current/sql-notify.html) · [`CREATE TRIGGER` transition tables](https://www.postgresql.org/docs/current/sql-createtrigger.html)
