# Getting Started — A Beginner's Guide

**Who this is for:** anyone who has never used Spring Cloud Config, and maybe has never thought
much about configuration at all. No prior knowledge assumed. If a term appears, it gets explained
before it gets used.

If you already know what a Config Server and a refresh scope are, you want
[README.md](README.md) instead — it is the reference, and it is dense on purpose.

**Contents**

1. [The problem, in plain terms](#1-the-problem-in-plain-terms)
2. [What "external configuration" means](#2-what-external-configuration-means)
3. [The vocabulary](#3-the-vocabulary)
4. [The cast: what actually runs](#4-the-cast-what-actually-runs)
5. [How a change travels](#5-how-a-change-travels)
6. [Your first run](#6-your-first-run)
7. [Change a setting and watch it land](#7-change-a-setting-and-watch-it-land)
8. [The interesting part: what happens when the change is wrong](#8-the-interesting-part-what-happens-when-the-change-is-wrong)
9. [Three versions, one idea](#9-three-versions-one-idea)
10. [Where to go next](#10-where-to-go-next)
11. [When things go wrong](#11-when-things-go-wrong)

---

## 1. The problem, in plain terms

Imagine you work at an online store. Your warehouse team calls on a Friday evening:

> "Stop accepting orders bigger than 500 units. Right now."

The limit — 500 — is a number your code checks on every order. Where does that number live?

**Option A: hardcoded in the Java source.**

```java
if (quantity > 500) {          // the number lives here
    throw new OrderQuantityExceededException();
}
```

To change it you edit the code, commit, wait for the build, wait for the tests, produce a new
release, and deploy it. Best case that is twenty minutes. Realistically, on a Friday evening, it
is tomorrow.

**Option B: in a settings file shipped inside the application.**

Better — no code change. But the file is *inside* the deployable unit, so changing it still means
building and deploying a new version. Same twenty minutes.

**Option C: keep the number outside the application entirely.**

The application asks some central place "what is the order limit?" when it starts up. Now
changing the limit means changing it in that one central place. No rebuild, no new release.

But there is still a catch, and it is the catch this whole project is about: **the application
asked once, at startup.** It is still holding the old answer. To pick up the new one it has to
restart — which means dropping in-flight requests and, if you have ten copies running, restarting
all ten.

**Option D — what this project builds:** keep the number outside the application, *and* have every
running copy notice the change and start using the new value **within a second, without
restarting anything.**

That is it. That is the entire goal. Everything else in this repository is the machinery to do
that safely.

---

## 2. What "external configuration" means

"Configuration" is any value your program needs but that isn't really *logic*:

- the maximum order quantity (500)
- which warehouse to ship from (`WH-BLR-01`)
- whether express shipping is switched on (true/false)
- a password for some other service you call

"**External** configuration" means those values live outside your application's code and outside
its deployable package. The application fetches them at runtime.

Why bother? Three reasons that matter in practice:

| Reason | What it gets you |
|---|---|
| **One artifact, many environments** | The exact same tested build runs in test and in production. Only the configuration differs. You are never shipping a "production build" that nobody tested. |
| **Change without deploying** | Operations people can adjust a limit or flip a feature on without a developer, a build, or a release. |
| **Secrets stay out of source control** | A database password never sits in a Git repository that every developer can read. |

This idea is old and well-established — it is principle III of the
[Twelve-Factor App](https://12factor.net/config), if you want the canonical write-up.

---

## 3. The vocabulary

These eight terms are all you need to read the rest of this guide. Everything else in the other
documents is built on top of them.

**Property** — one named value. `inventory.max-order-quantity = 500`. That's a property. Its name
is on the left, its value on the right.

**Configuration Server** (or "Config Server") — a small web application whose entire job is to
hold properties and hand them out. Your services ask it "what are my settings?" and it answers.
In this project it runs on port `8888`. It is a real, standard piece of Spring software
(*Spring Cloud Config Server*) — not something invented here.

**Client** — any application that *asks* the Config Server for its settings. This project has two
clients: `inventory-service` and `pricing-service`. Calling them "clients" just means they are on
the asking end.

**Backend** — where the Config Server actually stores the properties. Could be a Git repository, a
database, a cloud file store. The clients neither know nor care which one it is; they only ever
talk to the Config Server. **This project ships three different backends to prove exactly that
point.**

**Refresh** — the act of a running client re-asking the Config Server for its settings and
adopting the new answer, *without restarting*. This is the thing that is hard, and the thing this
project is about.

**The Bus** — a message channel that lets the Config Server shout "everybody refresh!" to all
clients at once. Without it, you'd have to poke each running copy individually. This project uses
RabbitMQ, a widely-used message broker, as the channel. The Spring piece that rides on it is
called *Spring Cloud Bus*.

**Snapshot** — a complete, frozen, read-only set of configuration values, taken at one moment.
This is a design idea specific to this project, and it is the heart of it: instead of letting
business code read settings that might be changing underneath it, the code reads a *snapshot*
that can never change. When new configuration arrives, a whole new snapshot is built and swapped
in, in one step.

**Last-known-good** — the snapshot the service is currently using successfully. If someone pushes
a broken configuration change, the service *keeps* its last-known-good snapshot and carries on
serving traffic, rather than adopting the broken values or falling over. Section 8 shows this
happening.

---

## 4. The cast: what actually runs

When you start version A, five containers come up. Here is what each one is for.

```text
        ┌──────────────────────────┐
        │   config-repo/  (Git)    │   ← the settings live here, as .yml text files
        └────────────┬─────────────┘      you edit these
                     │ reads
        ┌────────────▼─────────────┐
        │      Config Server       │   port 8888
        │  "what are my settings?" │   reads the backend, answers clients
        └────────────┬─────────────┘
                     │ "everybody refresh!"
        ┌────────────▼─────────────┐
        │     RabbitMQ (the Bus)   │   port 5672
        └──┬──────────┬─────────┬──┘
           │          │         │
    ┌──────▼───┐ ┌────▼─────┐ ┌─▼─────────┐
    │inventory │ │ pricing  │ │ pricing   │   the clients — real little web apps
    │  :8081   │ │  :8082   │ │  #2 :8083 │   with actual business endpoints
    └──────────┘ └──────────┘ └───────────┘
```

Why two copies of `pricing-service`? To prove that a refresh reaches **every** running copy, not
just whichever one you happen to poke. That is the whole reason the Bus exists. In real life you
run several copies of a service for capacity and redundancy, and a configuration change that only
reached one of them would be worse than useless — you'd have copies disagreeing with each other.

The two client services do deliberately boring, easy-to-check things:

- **`inventory-service`** — accepts stock reservations, and refuses ones bigger than the
  configured limit. Also decides express vs standard shipping based on a configured on/off flag.
- **`pricing-service`** — quotes a price for an item, applying a configured discount percentage
  and an optional surge multiplier.

They are intentionally trivial. The point is not the business logic; the point is that you can
*see* their behaviour change the instant you change configuration.

---

## 5. How a change travels

You edit a file. About half a second later, three running services behave differently. Here is
every step in between.

```text
 1. You edit config-repo/inventory-service.yml and commit it to Git
                         │
 2. Git runs a "post-commit hook" — a script that fires automatically after every commit
                         │
 3. The hook calls the Config Server's /monitor endpoint: "something changed"
                         │
 4. The Config Server works out WHICH application the changed file belongs to,
    and publishes a refresh message onto the Bus, addressed to just that application
                         │
 5. Every running copy of inventory-service receives the message
                         │
 6. Each one re-fetches its settings from the Config Server
                         │
 7. Each one validates the new values, builds a fresh snapshot, and swaps it in
                         │
 8. The very next request is served using the new values.  No restart happened.
```

Two details in there are worth pausing on, because they are the difference between a demo and
something you'd actually run:

**Step 4 — "addressed to just that application."** Changing `inventory-service.yml` refreshes
only the inventory service. The pricing service is left alone. This matters because a refresh is
not free, and you do not want every configuration change in your company to ripple through every
service you own. There is also a shared file, `application.yml`, whose changes deliberately *do*
reach everyone.

**Step 7 — "validates the new values."** Configuration comes from outside your code, which means
it can be wrong. Someone can type `99999` where the maximum sensible value is `10000`. Section 8
is entirely about what happens then.

---

## 6. Your first run

### What you need first

| Tool | Why | Check it with |
|---|---|---|
| Docker (running) | Everything runs in containers | `docker ps` |
| Java 21 | The services are Java 21 | `java -version` |
| Maven 3.9+ | Builds the code | `mvn -version` |
| `curl` and `jq` | Calling the services and reading the JSON they return | `curl --version`, `jq --version` |

`jq` is only there to pretty-print JSON. If you don't have it, drop the `| jq .` from the commands
below and you will get the same data, just harder to read.

### Start it

We'll use **version A**, the Git-backed one. It is the easiest to understand because the
configuration is just text files in a Git repository, and you already know how Git works.

```bash
cd version-a-git
```

**Step 1 — turn `config-repo/` into a real Git repository.**

```bash
git -C config-repo init -b main
git -C config-repo add -A && git -C config-repo commit -m "Seed configuration"
```

This is a one-time step, and it needs a word of explanation. `config-repo/` is the folder holding
your settings, and the Config Server reads it *as a Git repository* — so it has to actually be
one. It ships as plain files rather than a nested Git repo, because a repo inside a repo doesn't
survive being cloned (Git would hand you an empty placeholder instead of the files).

**Step 2 — build the code.**

```bash
mvn clean install -DskipTests
```

Skipping tests here just makes the first run faster. Run `mvn verify` later if you want to see the
full test suite, which is worth doing.

This one command builds all three services. Each one is also a self-contained project, so you can
build just the one you care about — `cd inventory-service && mvn verify` — and it needs nothing
from its siblings.

**Step 3 — create an encryption key.**

```bash
./scripts/generate-keystore.sh
```

One of the settings is a password (`downstream-api-key`). It is stored **encrypted** in the
configuration file — if you open `config-repo/inventory-service.yml` you'll see a long
unreadable string starting with `{cipher}`. The Config Server holds the only copy of the key that
decrypts it, and decrypts it before handing it to a client. So the encrypted form is what sits in
Git, where anyone can read it, and that's fine. This script creates that key.

**Step 4 — install the Git hook.**

```bash
./scripts/install-git-hook.sh
```

This is step 2 of the journey in section 5: a small script that Git runs automatically after every
commit, which tells the Config Server that something changed. Without it, everything still works —
you would just have to trigger refreshes by hand.

**Step 5 — start everything.**

```bash
docker compose -f docker/compose.yaml up -d --build
```

`-d` means "in the background". The first run has to build container images, so give it a few
minutes. Watch them come up with:

```bash
docker compose -f docker/compose.yaml ps
```

Wait until the client services report healthy. You can also just watch the logs:

```bash
docker compose -f docker/compose.yaml logs -f inventory-service
```

**Step 6 — prove it all works.**

```bash
./scripts/e2e-test.sh
```

This runs the full acceptance suite against the live stack — every claim in the README, checked
end to end. It is the fastest way to know your setup is sound. It is safe to run repeatedly.

### Look around

Ask the inventory service what settings it is currently using:

```bash
curl -s localhost:8081/api/v1/config/snapshot | jq .
```

```json
{
  "application": "inventory-service",
  "version": 1,
  "appliedAt": "2026-08-30T15:00:13.154Z",
  "lastOutcome": "APPLIED",
  "refreshAttempts": 0,
  "rejectedCount": 0,
  "lastChangedKeys": [],
  "settings": {
    "warehouseCode": "WH-BLR-01",
    "maxOrderQuantity": 500,
    "expressShippingEnabled": true,
    "lowStockThreshold": 25,
    "bannerMessage": "Configured centrally via Spring Cloud Config - Git backend",
    "environmentLabel": "production-like",
    "downstreamApiKeyFingerprint": "sha256:..."
  }
}
```

Read that response field by field, because you will be using it for the rest of the guide:

- **`settings`** — the values the service is using right now. Compare them against
  `config-repo/inventory-service.yml`; they should match.
- **`version`** — a counter that starts at 1 and goes up **only when a value actually changed**.
  This is your single most useful tool. To prove a change landed, compare the version before and
  after.
- **`lastOutcome`** — what happened on the most recent refresh attempt. `APPLIED` (new values
  adopted), `NO_CHANGE` (asked, but nothing was different), or `REJECTED` (new values were
  invalid and were refused — section 8).
- **`rejectedCount`** — how many times bad configuration has been refused.
- **`downstreamApiKeyFingerprint`** — note what is *not* here: the actual password. This endpoint
  shows a one-way hash of it instead, so you can confirm the right secret arrived without the
  endpoint leaking it.

Now call an actual business endpoint and watch configuration drive behaviour:

```bash
curl -s -X POST localhost:8081/api/v1/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1","quantity":10}' | jq .
```

```json
{
  "reservationId": "...",
  "sku": "SKU-1",
  "quantity": 10,
  "warehouseCode": "WH-BLR-01",
  "expressEligible": true,
  "shippingMode": "EXPRESS",
  "lowStockWarning": false,
  "appliedMaxOrderQuantity": 500,
  "configVersion": 1
}
```

Every one of those fields traces back to a configured value. And `configVersion` tells you *which
generation of configuration* served this particular request — useful when you are trying to work
out whether a request happened before or after a change.

Now ask for more than the limit allows:

```bash
curl -s -X POST localhost:8081/api/v1/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1","quantity":501}' | jq .
```

You get a clean, structured error saying 501 was requested and 500 is allowed. Remember that
`500` — we are about to change it while the service keeps running.

---

## 7. Change a setting and watch it land

**First, note where you are:**

```bash
curl -s localhost:8081/api/v1/config/snapshot | jq '.version, .settings.maxOrderQuantity'
```

Say that gives you `1` and `500`.

**Now change the limit.** Open `config-repo/inventory-service.yml` in any editor and change
`max-order-quantity` from `500` to `750`:

```yaml
inventory:
  warehouse-code: "WH-BLR-01"
  max-order-quantity: 750      # was 500
  express-shipping-enabled: true
  low-stock-threshold: 25
```

**Commit it.** This is the part that triggers everything:

```bash
git -C config-repo commit -am "Raise the order limit to 750"
```

**Look again** — give it a second or so:

```bash
curl -s localhost:8081/api/v1/config/snapshot | jq '.version, .settings.maxOrderQuantity, .lastOutcome'
```

You should now see `2`, `750`, and `"APPLIED"`.

**Nothing restarted.** Confirm that for yourself — the uptime and restart count are untouched:

```bash
docker compose -f docker/compose.yaml ps inventory-service
```

**And the business behaviour has changed.** The request that was rejected a minute ago now
succeeds:

```bash
curl -s -X POST localhost:8081/api/v1/inventory/reservations \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1","quantity":501}' | jq .
```

That is the whole point of the project, and you just watched it happen.

### Two things to try while you're here

**Check that the change was scoped.** The pricing service should be entirely unaffected — its
version should not have moved:

```bash
curl -s localhost:8082/api/v1/config/snapshot | jq '.application, .version'
```

**Check that it reached every copy.** Both pricing instances should agree. Change something in
`config-repo/pricing-service.yml`, commit, then ask both:

```bash
curl -s localhost:8082/api/v1/config/snapshot | jq '.version, .settings.discountPercentage'
curl -s localhost:8083/api/v1/config/snapshot | jq '.version, .settings.discountPercentage'
```

Same numbers from both. That is the Bus doing its job.

### Why commit, and not just save?

With this Git backend, the Config Server reads the *working tree* — the files as they sit on disk.
So the moment you **save** the file, the Config Server would already serve the new value to
anyone who asked it directly.

What the **commit** does is fire the post-commit hook, which is the only thing that tells the
already-running clients to go and re-ask. Saving changes what the server *would say*; committing
is what makes the clients *listen*. If you save without committing, nothing appears to happen —
and now you know why.

---

## 8. The interesting part: what happens when the change is wrong

Everything so far was the happy path. This section is the one worth actually understanding,
because it is where most real-world configuration systems quietly fail.

Configuration comes from outside your code. Which means it can be **wrong**. Someone fat-fingers
an extra digit at 11pm. What should happen?

The maximum sensible order quantity in this service is 10,000. Let's push 99,999.

```bash
# note the version and value you're starting from
curl -s localhost:8081/api/v1/config/snapshot | jq '.version, .settings.maxOrderQuantity'
```

Edit `config-repo/inventory-service.yml` to set `max-order-quantity: 99999`, then commit:

```bash
git -C config-repo commit -am "Oops, typo"
```

Now look:

```bash
curl -s localhost:8081/api/v1/config/snapshot | jq '.version, .settings.maxOrderQuantity, .lastOutcome, .lastFailureReason'
```

You will see something like:

```text
2                                                      ← version did NOT go up
750                                                    ← still the OLD, good value
"REJECTED"
"maxOrderQuantity must be less than or equal to 10000"
```

Read what just happened carefully, because four separate good things occurred:

1. **The bad value was refused.** The service did not adopt 99,999.
2. **The service kept working.** It is still serving traffic, using the last configuration that
   was valid — its *last-known-good* snapshot. It did not crash, and it did not fall back to
   defaults or empty values.
3. **The version did not move.** So your "did it change?" check stays honest.
4. **It told you exactly what was wrong**, in `lastFailureReason`, naming the offending property.

And one more, which is easy to miss and genuinely a judgement call:

```bash
curl -s localhost:9081/actuator/health | jq '.status, .components.configuration'
```

The service reports **UP**, not DOWN — while clearly flagging `lastOutcome: REJECTED` and the
reason in its health details.

That is deliberate. Health status is what load balancers and Kubernetes use to decide whether to
send you traffic or kill your container. This service is *perfectly healthy* — it is serving
correct, valid, slightly-older configuration. The broken thing is a file in a Git repository. If
it reported DOWN, a bad commit would take your entire fleet out of service, which is a far worse
outcome than running on configuration that is five minutes stale. So it stays UP and shouts
loudly in its details instead.

**Now fix it.** Set the value back to something valid and commit:

```bash
git -C config-repo commit -am "Fix the typo"
curl -s localhost:8081/api/v1/config/snapshot | jq '.version, .settings.maxOrderQuantity, .lastOutcome'
```

Version moves, value updates, `lastOutcome` returns to `APPLIED`. It recovers on its own; no
intervention, no restart.

### The audit trail

Every refresh attempt — applied, unchanged, or rejected — is recorded:

```bash
curl -s localhost:8081/api/v1/config/history | jq '.[0:3]'
```

You will see the rejection you just caused, with a timestamp, the reason, and which property keys
changed. Note that it records property **names** only, never **values** — because one of those
values is a decrypted password, and an audit log is not a safe place to put it.

---

## 9. Three versions, one idea

You have been using version A. There are three, and the difference between them is *only* where
the settings are stored and how a change is detected:

| | Where settings live | How a change is noticed | How you change one |
|---|---|---|---|
| **A** — [version-a-git](version-a-git/) | Text files in a Git repository | Git's post-commit hook calls the server | `git commit` |
| **B** — [version-b-jdbc](version-b-jdbc/) | Rows in a PostgreSQL database | A database trigger fires a notification the server is listening for | `UPDATE` a row |
| **C** — [version-c-s3](version-c-s3/) | Files in AWS S3 (cloud storage) | S3 sends an event to a queue the server reads | Upload a file |

Here is the part that matters, and it is the real lesson of the project:

**The two client services are byte-for-byte identical across all three versions.** Not "similar" —
identical files. They have no idea whether their configuration came from Git, Postgres, or S3. They
ask the Config Server, and the Config Server deals with it.

That is what a good abstraction looks like. You can swap out the entire storage layer of your
configuration system and not touch, retest, or redeploy a single line of application code. If you
take one idea away from this repository, take that one.

Each version runs on its own ports, so you can run all three side by side if you have the disk
space: A on 8888/8081-8083, B on 8898/8091-8093, C on 8908/8101-8103.

### Cleaning up

```bash
docker compose -f docker/compose.yaml down -v
```

---

## 10. Where to go next

Now that the vocabulary means something to you, the other documents will read much more easily.
In rough order of how approachable they are:

1. **[README.md](README.md) §4, "The core design"** — start here. It explains the three
   engineering decisions that make refresh *safe* rather than just possible: why the settings
   class must use setters instead of being a `record`, why validation deliberately does *not* go
   on that class, and why every read goes through a single atomic reference. Each one exists
   because of a specific, non-obvious way Spring Cloud behaves. This is the most valuable reading
   in the repository.

2. **The code itself** — one file: `inventory-service/.../provider/InventorySettingsProvider.java`.
   It is the whole refresh algorithm in one class: validate, compare, swap, audit. The comments
   explain *why* at each step, not just what.

3. **The tests** — `InventorySettingsProviderTest`. Read the test names alone and you have a
   specification of the safety guarantees. One of them spins up eight concurrent reader threads to
   prove no request can ever see a half-applied set of values.

4. **[README.md](README.md) §8, the operator runbook** — how to change configuration, roll back,
   and recover by hand, in each of the three versions.

5. **[REQUIREMENTS.md](REQUIREMENTS.md)** — what was asked for, before any code existed.

6. **[SPECIFICATION.md](SPECIFICATION.md)** and
   **[SPECIFICATION-BACKENDS.md](SPECIFICATION-BACKENDS.md)** — the deep technical design. Dense,
   precise, written for someone implementing this. Worth it when you need the detail.

---

## 11. When things go wrong

**A client container won't start.**
It is meant to fail fast rather than start up with no configuration, so the usual cause is that it
cannot reach the Config Server. Check the server is healthy first:

```bash
curl -s localhost:8888/actuator/health | jq .
docker compose -f docker/compose.yaml logs config-server | tail -30
```

**I committed, but the version didn't change.**
Work through it in this order:

1. Did the value *actually* change? An identical value is correctly reported as `NO_CHANGE` —
   nothing is broken.
2. Was it `REJECTED`? Check `lastOutcome` and `lastFailureReason` (section 8).
3. Is the Git hook installed? `ls -l config-repo/.git/hooks/post-commit`. If it's missing, re-run
   `./scripts/install-git-hook.sh`.
4. Is RabbitMQ up? `docker compose -f docker/compose.yaml ps rabbitmq`.
5. Force it by hand and see whether that works — if it does, the problem is in the trigger
   chain, not in the refresh itself:

   ```bash
   curl -X POST localhost:9081/actuator/refresh
   ```

**Only one of the two pricing copies updated.**
That points at the Bus rather than the services. Check RabbitMQ is healthy and look at its
management console at <http://localhost:15672> (guest/guest).

**I need to refresh everything by hand.**

```bash
# one specific instance
curl -X POST localhost:9081/actuator/refresh

# every copy of one application, via the Bus
curl -X POST -u config-admin:admin-secret \
     localhost:8888/actuator/busrefresh/inventory-service
```

**Something is deeply wrong and I want a clean slate.**

```bash
docker compose -f docker/compose.yaml down -v
docker compose -f docker/compose.yaml up -d --build
```

**Port already in use.** All three versions can run at once, but if you have something else on
8888 or 8081-8083, stop it — or run version B or C instead, which use different ports.

---

*Found something in this guide that didn't match what you saw? That's a documentation bug worth
reporting — this guide is meant to be followable exactly as written.*
