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

These terms are all you need to read the rest of this guide. Everything else in the other
documents is built on top of them.

**Property** — one named value. `inventory.max-order-quantity = 500`. That's a property. Its name
is on the left, its value on the right.

**Configuration Server** (or "Config Server") — a small web application whose entire job is to
hold properties and hand them out. Your services ask it "what are my settings?" and it answers.
In this project it runs on port `8888`. It is a real, standard piece of Spring software
(*Spring Cloud Config Server*) — not something invented here.

**Client** — any application that *asks* the Config Server for its settings. This project has
five clients, in three languages: `inventory-service` and `pricing-service` (Java, Spring Boot),
`node-service` (Node.js), `go-service` (Go), and `lambda-service` (an AWS Lambda function).
Calling them "clients" just means they are on the asking end.

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

**Validation** — checking that a setting makes sense before relying on it. Each client has rules
such as "the maximum order quantity is between 1 and 10,000". Section 8 shows what each client does
when a setting breaks a rule.

**Lambda** — a function that AWS runs on demand and freezes between calls. Because it is frozen, it
cannot listen to the Bus; instead it asks the Config Server on *every* call. Here it runs in
**Floci**, a program that imitates AWS on your laptop.

---

## 4. The cast: what actually runs

When you start version A, six containers come up, and one Lambda function runs in Floci. Here is
what each one is for.

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
        └──┬────────┬────────┬──┬──┘
           │        │        │  │
    ┌──────▼──┐ ┌───▼────┐ ┌─▼──────┐ ┌▼──────┐      ┌─────────────────────┐
    │inventory│ │pricing │ │ node   │ │  go   │      │ lambda-service      │
    │ :8081   │ │ :8082  │ │ :8084  │ │ :8085 │      │ (in Floci) - asks   │
    │ Java    │ │ Java   │ │Node.js │ │  Go   │      │ the server on every │
    └─────────┘ └────────┘ └────────┘ └───────┘      │ call, no Bus needed │
                                                     └─────────────────────┘
```

Every client does the same deliberately simple thing: it has **one** web address that tells you
which settings it is using right now. For example, `inventory-service` answers
`GET /api/v1/inventory/config` with its four settings, and nothing else.

Why so simple? Because the point is not the business logic - the point is that you can *see* a
service's settings change the instant you change the configuration, without restarting it. And
because there are clients in three languages, you can see that the idea is not tied to Java.

---

## 5. How a change travels

You edit a file. About a second later, the service that owns that setting is using the new value. Here is
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
    (node-service and go-service hear it too, see it is not for them, and ignore it)
                         │
 6. Each one re-fetches its settings from the Config Server
                         │
 7. Each one checks the new values against its rules and starts using them
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

**Step 7 — "checks the new values."** Configuration comes from outside your code, which means
it can be wrong. Someone can type `99999` where the maximum sensible value is `10000`. Section 8
is entirely about what happens then.

**The Node.js and Go services are not Spring applications**, yet they follow the same steps. Spring
Cloud Bus is just small JSON messages on RabbitMQ, so they listen to it with a few lines of their
own code. Their READMEs (`node-service/README.md`, `go-service/README.md`) show exactly how.

---

## 6. Your first run

### What you need first

| Tool | Why | Check it with |
|---|---|---|
| Docker (running) | Everything runs in containers | `docker ps` |
| Java 21 | The services are Java 21 | `java -version` |
| Maven 3.9+ | Builds the code | `mvn -version` |
| `curl` and `jq` | Calling the services and reading the JSON they return | `curl --version`, `jq --version` |
| Floci and the AWS CLI | Only for lambda-service (the local AWS imitation) | `floci status`, `aws --version` |

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

The Config Server can store secrets **encrypted**: you write a long unreadable string starting
with `{cipher}` into the configuration file, the Config Server holds the only copy of the key that
decrypts it, and it decrypts the value before handing it to a client. So the encrypted form is what
sits in Git, where anyone can read it, and that's fine. This script creates that key.

None of the settings in this demo are encrypted right now, so nothing depends on the key being
correct. You still need to run this script, because the Config Server is configured to load the
keystore at startup and will not start without it. If you want to try encryption yourself, see the
`/encrypt` walkthrough in `version-a-git/README.md`.

**Step 4 — install the Git hook.**

```bash
./scripts/install-git-hook.sh
```

This is step 2 of the journey in section 5: a small script that Git runs automatically after every
commit, which tells the Config Server that something changed. Without it, everything still works —
you would just have to trigger refreshes by hand.

**Step 5 — start everything.**

```bash
CONFIG_REPO_URI=file:///config-repo CONFIG_REPO_SEARCH_PATHS= CONFIG_REPO_FORCE_PULL=false \
  docker compose -f docker/compose.yaml up -d --build
```

The three settings at the front tell the Config Server to read **your local `config-repo/`
folder**, so that a commit on your laptop is enough to change a value. (Without them it reads this
project's repository on GitHub, and you would have to `git push` every change.)

`-d` means "in the background". The first run has to build container images, so give it a few
minutes. Watch them come up with:

```bash
docker compose -f docker/compose.yaml ps
```

Wait until all six report `healthy`. You can also just watch the logs:

```bash
docker compose -f docker/compose.yaml logs -f inventory-service
```

> **At work, behind a corporate proxy such as Zscaler?** Building node-service and go-service may
> fail with `x509: certificate signed by unknown authority`, because the proxy re-signs internet
> traffic with its own certificate. Hand Docker that certificate and build again:
> `export EXTRA_CA_CERT="$(cat proxy-root.pem)"` (on a Mac:
> `export EXTRA_CA_CERT="$(security find-certificate -a -c Zscaler -p /Library/Keychains/System.keychain)"`).

**Step 6 — put lambda-service into Floci** (skip this if you do not have Floci; everything else
works without it):

```bash
floci start
./lambda-service/scripts/deploy-floci.sh
./lambda-service/scripts/invoke-floci.sh
```

The last command prints `{"greeting":"Hello from AWS Lambda","featureEnabled":true,"maxItems":10}`.

**Step 7 — prove it all works.**

```bash
./scripts/e2e-test.sh                 # or: SKIP_LAMBDA=1 ./scripts/e2e-test.sh  without Floci
```

This runs the full acceptance suite against the live stack — 36 checks, end to end. It is the
fastest way to know your setup is sound. It is safe to run repeatedly.

### Look around

Ask the inventory service which settings it is using right now:

```bash
curl -s localhost:8081/api/v1/inventory/config | jq .
```

```json
{
  "warehouseCode": "WH-BLR-01",
  "maxOrderQuantity": 100,
  "expressShippingEnabled": true,
  "lowStockThreshold": 115
}
```

Open `config-repo/inventory-service.yml` and compare - the same four values, under `inventory:`.
That is the whole answer: only this service's own settings.

Now ask the other clients the same question:

```bash
curl -s localhost:8082/api/v1/pricing/config | jq .
curl -s localhost:8084/api/v1/node/config | jq .
curl -s localhost:8085/api/v1/go/config | jq .
```

Each comes from its own file in `config-repo/` (`pricing-service.yml`, `node-service.yml`,
`go-service.yml`). Two of those services are written in Node.js and Go - and they got their
settings from the same Config Server, the same way.

If you want to see what the Config Server itself sends, ask it directly (it wants a password):

```bash
curl -s -u config-client:client-secret localhost:8888/node-service/default/main | jq .
```

---

## 7. Change a setting and watch it land

**First, note where you are:**

```bash
curl -s localhost:8081/api/v1/inventory/config | jq .maxOrderQuantity
```

That gives you `100`.

**Now change the limit.** Open `config-repo/inventory-service.yml` in any editor and change
`max-order-quantity` from `100` to `750`:

```yaml
inventory:
  warehouse-code: "WH-BLR-01"
  max-order-quantity: 750      # was 100
  express-shipping-enabled: true
  low-stock-threshold: 115
```

**Commit it.** This is the part that triggers everything:

```bash
git -C config-repo commit -am "Raise the order limit to 750"
```

**Look again** — give it a second or so:

```bash
curl -s localhost:8081/api/v1/inventory/config | jq .maxOrderQuantity
```

You should now see `750`.

**Nothing restarted.** Confirm that for yourself — the uptime is untouched:

```bash
docker compose -f docker/compose.yaml ps inventory-service
```

That is the whole point of the project, and you just watched it happen.

### Two things to try while you're here

**Check that the change was scoped.** The pricing service should be entirely unaffected:

```bash
curl -s localhost:8082/api/v1/pricing/config | jq .
```

**Watch a Node.js service do the same.** Change `max-items` in `config-repo/node-service.yml` from
`25` to `30`, commit, and ask it:

```bash
git -C config-repo commit -am "Change node-service max-items"
curl -s localhost:8084/api/v1/node/config | jq .maxItems           # 30
docker compose -f docker/compose.yaml logs --tail=2 node-service   # "Configuration changed"
```

The same broadcast that refreshes the Java services refreshed a Node.js service. Try `go-service.yml`
next - or `lambda-service.yml` and `./lambda-service/scripts/invoke-floci.sh`, which shows the new
value on its very next call.

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

Everything so far was the happy path. Configuration comes from outside your code, which means it
can be **wrong**. Someone fat-fingers an extra digit at 11pm. What should happen?

The maximum sensible order quantity in this service is 10,000. Let's commit 99,999 - and, at the
same time, an impossible `max-items: 0` for node-service.

Edit `config-repo/inventory-service.yml` to `max-order-quantity: 99999` and
`config-repo/node-service.yml` to `max-items: 0`, then commit:

```bash
git -C config-repo commit -am "Oops, typos"
```

Now look at both:

```bash
curl -s localhost:8081/api/v1/inventory/config | jq .maxOrderQuantity    # 99999
curl -s localhost:8084/api/v1/node/config | jq .maxItems                 # still 25
```

The two services made **different, deliberate choices**, and both tell you about it in their logs:

```bash
docker compose -f docker/compose.yaml logs inventory-service | grep invalid
#  ERROR ... Refreshed inventory configuration is invalid:
#            inventory.maxOrderQuantity must be less than or equal to 10000
docker compose -f docker/compose.yaml logs node-service | grep "refresh failed"
#  {"level":"error","message":"Configuration refresh failed; keeping the values in use",
#   "error":"Invalid node-service configuration: node.max-items must be an integer between 1 and 1000"}
```

| | inventory-service (Spring Boot) | node-service / go-service |
|---|---|---|
| What it serves | the new, invalid value | **the previous, valid value** |
| What it tells you | an `ERROR` log line naming the broken rule | an `error` log line naming the broken rule |
| Does it restart or go down? | no | no |

Why the difference? The Spring services are kept deliberately simple: Spring Cloud updates their
settings in place, and they check *afterwards*. The Node.js and Go services check *before* they
swap the new values in, so a bad set never replaces a good one. Both are reasonable; what matters
is that neither crashes and both say exactly what is wrong. (README §4.3 explains the trade-off.)

**Now the safety net that always holds: a service never *starts* on bad settings.** Restart
inventory-service while 99,999 is still in the file:

```bash
docker restart cfg-git-inventory
docker logs cfg-git-inventory 2>&1 | grep "Invalid inventory configuration"
#  InvalidConfigurationException: Invalid inventory configuration:
#    inventory.maxOrderQuantity must be less than or equal to 10000
docker compose -f docker/compose.yaml ps -a inventory-service   # "exited", not running
```

It refuses to start, and says why. That is the right behaviour: better no service than one that
silently runs on settings it cannot honour.

**Now fix it.** Put the values back (`100` and `25`), commit, and start inventory-service again:

```bash
git -C config-repo commit -am "Fix the typos"
docker start cfg-git-inventory
curl -s localhost:8081/api/v1/inventory/config | jq .maxOrderQuantity   # 100 (once it is up)
curl -s localhost:8084/api/v1/node/config | jq .maxItems               # 25
```

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

**The five client services are the same code in all three versions** - the only differences are
default port numbers. They have no idea whether their configuration came from Git, Postgres, or
S3. They ask the Config Server, and the Config Server deals with it.

That is what a good abstraction looks like. You can swap out the entire storage layer of your
configuration system and not touch, retest, or redeploy a single line of application code. If you
take one idea away from this repository, take that one.

Each version runs on its own ports, so you can run all three side by side if you have the disk
space: A on 8888 / 8081-8085, B on 8898 / 8091-8095, C on 8908 / 8101-8105.

### Cleaning up

```bash
docker compose -f docker/compose.yaml down -v
```

---

## 10. Where to go next

Now that the vocabulary means something to you, the other documents will read much more easily.
In rough order of how approachable they are:

1. **[README.md](README.md) §4, "The core design"** — start here. It explains why the settings
   class must use setters instead of being a `record`, why the validation rules deliberately do
   *not* switch on Spring's `@Validated`, the trade-off that was accepted to keep the clients
   simple, and how the Node.js and Go clients join Spring Cloud Bus without a Spring library.

2. **The code itself** — each client is small. Start with `inventory-service/src/main/java/.../config/InventoryProperties.java`
   and `InventoryPropertiesValidator.java`, then `node-service/src/bus/bus-listener.js` to see the
   Bus from the other side.

3. **Each service's README** — for example [node-service/README.md](version-a-git/node-service/README.md):
   its folder layout, settings, the four ways to run it (Docker Compose, Kubernetes, or straight
   on your machine), and the real error messages you might meet.

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

**I committed, but the value didn't change.**
Work through it in this order:

1. Did you start the stack in **local mode** (section 6, Step 5)? Without the three settings the
   Config Server reads GitHub, and a local commit does nothing.
2. Did the value break a rule? Look for `invalid` / `refresh failed` in the service's log
   (section 8).
3. Is the Git hook installed? `ls -l config-repo/.git/hooks/post-commit`. If it's missing, re-run
   `./scripts/install-git-hook.sh`.
4. Is RabbitMQ up? `docker compose -f docker/compose.yaml ps rabbitmq`.
5. Broadcast by hand and see whether that works — if it does, the problem is in the trigger chain,
   not in the refresh itself:

   ```bash
   curl -i -X POST -u config-admin:admin-secret -H "Content-Type: application/json" \
        localhost:9898/actuator/busrefresh
   ```
   Expect `HTTP/1.1 204`. (Leave out the `Content-Type` header and you get `415` and nothing
   happens.)

**A Node.js or Go service never updates, but the Java ones do.**
Check its log for `Listening on Spring Cloud Bus`. If you see `Could not connect to RabbitMQ; will
retry` (Node.js) or `Spring Cloud Bus connection lost; will retry` (Go), it cannot reach the broker.

**lambda-service answers `503`.**
It could not reach the Config Server. The function runs inside Floci and reaches it through your
machine at `host.docker.internal:8888`, so the stack must be up.

**Something is deeply wrong and I want a clean slate.**

```bash
docker compose -f docker/compose.yaml down -v
docker compose -f docker/compose.yaml up -d --build
```

**Port already in use.** All three versions can run at once, but if you have something else on
8888 or 8081-8085, stop it — or run version B or C instead, which use different ports. (Version B's
database uses host port 5433, because a PostgreSQL installed on your machine usually holds 5432.)

---

*Found something in this guide that didn't match what you saw? That's a documentation bug worth
reporting — this guide is meant to be followable exactly as written.*
