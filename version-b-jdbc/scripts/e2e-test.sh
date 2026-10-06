#!/usr/bin/env bash
# End-to-end acceptance test for version B (PostgreSQL / JDBC backend).
#
#   # from: version-b-jdbc/
#   ./scripts/e2e-test.sh
#
# Proves the point of the project: UPDATE a row in the PostgreSQL "properties" table with psql,
# and every client that owns that value serves the new value within seconds - with no restart -
# while the clients that do not own it are untouched. No application code takes part in the
# write: a database trigger raises pg_notify, the Config Server listens and broadcasts a refresh.
# Covers all five clients:
#   inventory-service, pricing-service (Spring Boot)  -> refreshed over Spring Cloud Bus
#   node-service (Node.js), go-service (Go)            -> refreshed over Spring Cloud Bus
#   lambda-service (AWS Lambda in Floci)               -> reads the latest values on every call
#
# Needs: the Compose stack running (docker compose -f docker/compose.yaml up -d --build), plus
# lambda-service deployed to Floci (lambda-service/scripts/deploy-floci.sh), python3, curl.
# Set SKIP_LAMBDA=1 to skip the Lambda checks when Floci is not running.
#
# The suite updates the database. It sets a known baseline first and restores it at the end, so
# it can be run any number of times.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG="cfg-jdbc-postgres"
SERVER="http://localhost:8898"
SERVER_MGMT="http://localhost:9899"
CLIENT_AUTH="config-client:client-secret"
ADMIN_AUTH="config-admin:admin-secret"
INVENTORY="http://localhost:8091/api/v1/inventory/config"
PRICING="http://localhost:8092/api/v1/pricing/config"
NODE="http://localhost:8094/api/v1/node/config"
GO="http://localhost:8095/api/v1/go/config"
SLA_SECONDS=5
SKIP_LAMBDA=${SKIP_LAMBDA:-0}
LAMBDA_INVOKE="$HERE/../lambda-service/scripts/invoke-floci.sh"

PASS=0; FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
ok()      { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad()     { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
section() { echo; echo "${BOLD}$1${OFF}"; }

# json <field> : reads JSON on stdin and prints one top-level field ("" if absent or not JSON).
json() { python3 -c 'import sys,json
try: v=json.load(sys.stdin).get(sys.argv[1], "")
except Exception: v=""
print(str(v).lower() if isinstance(v,bool) else v)' "$1" 2>/dev/null; }

get()    { curl -s -m 5 "$1"; }
lambda() { "$LAMBDA_INVOKE" 2>/dev/null; }
status() { curl -s -m 5 -o /dev/null -w '%{http_code}' "$@"; }

# sql <statement> : runs SQL as the database owner, exactly as an operator would with psql.
sql() { docker exec -i "$PG" psql -U config_admin -d configdb -v ON_ERROR_STOP=1 -tAc "$1" 2>&1; }

# set_prop <application> <key> <value> : one UPDATE = one change notification.
set_prop() {
  local out
  out=$(sql "UPDATE properties SET \"value\"='$3' WHERE application='$1' AND \"key\"='$2' AND label='main' AND profile IS NULL;")
  [ "$out" = "UPDATE 1" ] || bad "UPDATE $1 $2 affected: $out"
}

# expect_within <description> <expected> <command...> : polls until the command prints the
# expected value, for at most SLA_SECONDS. Reports how long it took.
expect_within() {
  local description="$1" expected="$2"; shift 2
  local start=$SECONDS got=""
  while [ $((SECONDS - start)) -le "$SLA_SECONDS" ]; do
    got=$("$@")
    if [ "$got" = "$expected" ]; then
      ok "$description ($((SECONDS - start))s, SLA ${SLA_SECONDS}s)"
      return 0
    fi
    sleep 0.3
  done
  bad "$description: got '$got', expected '$expected' within ${SLA_SECONDS}s"
}

field_of() { get "$1" | json "$2"; }
lambda_field() { lambda | json "$1"; }

baseline() {
  set_prop inventory-service inventory.max-order-quantity 500
  set_prop pricing-service pricing.surge-pricing-enabled false
  set_prop node-service node.max-items 25
  set_prop go-service go.max-items 50
  set_prop lambda-service lambda.max-items 10
  set_prop inventory-service inventory.low-stock-threshold 25
  sleep 3
}
trap 'echo; echo "Restoring the baseline..."; baseline' EXIT

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version B (PostgreSQL backend) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ------------------------------------------------------------------------------ preconditions
section "Preconditions"
[ "$(get "$SERVER_MGMT/actuator/health" | json status)" = "UP" ] && ok "config-server is UP" || bad "config-server is not UP"
for name in inventory:9091 pricing:9092; do
  [ "$(get "http://localhost:${name#*:}/actuator/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
for name in node:8094 go:8095; do
  [ "$(get "http://localhost:${name#*:}/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
[ "$(sql 'SELECT count(*) FROM properties')" -ge 19 ] 2>/dev/null \
  && ok "PostgreSQL holds the seeded configuration (V1 + V3 migrations)" || bad "PostgreSQL seed data missing"
if [ "$SKIP_LAMBDA" = "1" ]; then
  echo "  ${YELLOW}SKIP${OFF}  lambda-service (SKIP_LAMBDA=1)"
elif [ -n "$(lambda_field greeting)" ]; then
  ok "lambda-service answers through Floci API Gateway"
else
  bad "lambda-service is not reachable - run lambda-service/scripts/deploy-floci.sh (or SKIP_LAMBDA=1)"
fi

section "Baseline"
baseline
echo "  configuration reset to the test baseline"

# ------------------------------------------------------------------------------ each client serves its own properties
section "Each client returns ONLY its own properties"
inventory_keys=$(get "$INVENTORY" | python3 -c 'import sys,json;print(",".join(sorted(json.load(sys.stdin))))' 2>/dev/null)
[ "$inventory_keys" = "expressShippingEnabled,lowStockThreshold,maxOrderQuantity,warehouseCode" ] \
  && ok "inventory-service: $inventory_keys" || bad "inventory-service keys: $inventory_keys"
pricing_keys=$(get "$PRICING" | python3 -c 'import sys,json;print(",".join(sorted(json.load(sys.stdin))))' 2>/dev/null)
[ "$pricing_keys" = "currency,discountPercentage,surgeMultiplier,surgePricingEnabled" ] \
  && ok "pricing-service: $pricing_keys" || bad "pricing-service keys: $pricing_keys"
[ "$(field_of "$NODE" greeting)" = "Hello from Node.js" ] && ok "node-service greets from Node.js" || bad "node-service greeting"
[ "$(field_of "$GO" greeting)" = "Hello from Go" ] && ok "go-service greets from Go" || bad "go-service greeting"
if [ "$SKIP_LAMBDA" != "1" ]; then
  [ "$(lambda_field greeting)" = "Hello from AWS Lambda" ] && ok "lambda-service greets from AWS Lambda" || bad "lambda-service greeting"
fi

# ------------------------------------------------------------------------------ live refresh, scoped
section "A change reaches only the service that owns it - live, no restart"
set_prop inventory-service inventory.max-order-quantity 750
expect_within "inventory-service maxOrderQuantity 500 -> 750 after psql UPDATE" 750 field_of "$INVENTORY" maxOrderQuantity
[ "$(field_of "$PRICING" surgePricingEnabled)" = "false" ] && ok "pricing-service untouched" || bad "pricing-service changed"
[ "$(field_of "$NODE" maxItems)" = "25" ] && ok "node-service untouched" || bad "node-service changed"

set_prop pricing-service pricing.surge-pricing-enabled true
expect_within "pricing-service surgePricingEnabled false -> true" true field_of "$PRICING" surgePricingEnabled
[ "$(field_of "$INVENTORY" maxOrderQuantity)" = "750" ] && ok "inventory-service untouched" || bad "inventory-service changed"

set_prop node-service node.max-items 30
expect_within "node-service (Node.js, over Spring Cloud Bus) maxItems 25 -> 30" 30 field_of "$NODE" maxItems
[ "$(field_of "$GO" maxItems)" = "50" ] && ok "go-service untouched" || bad "go-service changed"

set_prop go-service go.max-items 55
expect_within "go-service (Go, over Spring Cloud Bus) maxItems 50 -> 55" 55 field_of "$GO" maxItems
[ "$(field_of "$NODE" maxItems)" = "30" ] && ok "node-service untouched" || bad "node-service changed"

if [ "$SKIP_LAMBDA" != "1" ]; then
  set_prop lambda-service lambda.max-items 12
  expect_within "lambda-service (reads on every call) maxItems 10 -> 12" 12 lambda_field maxItems
fi

# ------------------------------------------------------------------------------ transactions
section "Only COMMITTED changes reach the clients"
# PostgreSQL delivers pg_notify only on COMMIT, so a rolled-back UPDATE must change nothing.
sql "BEGIN; UPDATE properties SET \"value\"='7777' WHERE application='inventory-service' AND \"key\"='inventory.max-order-quantity'; ROLLBACK;" >/dev/null
sleep 2
[ "$(field_of "$INVENTORY" maxOrderQuantity)" = "750" ] && ok "a rolled-back UPDATE is never served" || bad "inventory-service served an uncommitted value"

# ------------------------------------------------------------------------------ PostgreSQL-specific guarantees
broadcasts() { curl -s -m 5 -u "$ADMIN_AUTH" "$SERVER_MGMT/actuator/metrics/config.change.broadcast" \
  | python3 -c 'import sys,json;print(int(json.load(sys.stdin)["measurements"][0]["value"]))' 2>/dev/null || echo 0; }

section "A bulk UPDATE produces ONE broadcast, not one per row"
before=$(broadcasts)
sql "UPDATE properties SET updated_at=now() WHERE application='inventory-service';" >/dev/null
sleep 4
after=$(broadcasts)
rows=$(sql "SELECT count(*) FROM properties WHERE application='inventory-service';")
[ $((after - before)) -eq 1 ] && ok "exactly 1 broadcast for a $rows-row UPDATE (statement-level trigger)" \
                              || bad "expected 1 broadcast for a $rows-row UPDATE, got $((after - before))"

section "properties_history is the audit trail (it replaces git log)"
latest=$(sql "SELECT operation||'|'||application||'|'||\"key\"||'|'||coalesce(old_value,'-')||'->'||coalesce(new_value,'-')||'|'||changed_by FROM properties_history ORDER BY history_id DESC LIMIT 1;")
echo "  latest entry: $latest"
echo "$latest" | grep -qE '^U\|' && ok "records operation, application, key, old -> new value and who made it" \
                                  || bad "unexpected history entry: $latest"

section "A lost notification is still delivered (catch-up on reconnect, or the 15s reconciler)"
# Kill ONLY the Config Server's dedicated LISTEN session, then commit straight away: NOTIFY reaches
# only sessions connected at commit time, so this change is genuinely lost to the listener and
# a catch-up has to deliver it: the listener's revision check when it reconnects, or failing that
# the 15-second revision poller. (Measured: usually ~1s, i.e. the reconnect catch-up.)
killed=$(sql "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = 'config-notify-listener';")
[ "${killed:-0}" -ge 1 ] 2>/dev/null && ok "the LISTEN session was found by its application_name and killed" \
                                     || bad "no LISTEN session found (application_name=config-notify-listener)"
set_prop inventory-service inventory.low-stock-threshold 44
SLA_SECONDS=40
expect_within "inventory-service still receives the missed change (lowStockThreshold 25 -> 44)" 44 field_of "$INVENTORY" lowStockThreshold
SLA_SECONDS=5
drops=$(get "$SERVER_MGMT/actuator/health" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["configChange"]["details"]["listenerDrops"])' 2>/dev/null)
[ "${drops:-0}" -ge 1 ] 2>/dev/null && ok "the drop is visible in the Config Server's health (listenerDrops=$drops)" || bad "listenerDrops=${drops:-none}"
[ "$(get "$SERVER_MGMT/actuator/health" | json status)" = "UP" ] && ok "Config Server stays UP (a lost notification is not an outage)" || bad "Config Server not UP"

# ------------------------------------------------------------------------------ security and errors
section "Security and error format"
[ "$(status "$SERVER/inventory-service/default")" = "401" ] && ok "Config Server rejects unauthenticated reads" || bad "Config Server readable without credentials"
[ "$(status -H 'Content-Type: text/plain' --data-binary x "$SERVER/encrypt")" = "401" ] && ok "/encrypt requires authentication" || bad "/encrypt is open"
[ "$(status http://localhost:8091/swagger-ui/index.html)" = "200" ] && ok "inventory-service Swagger UI is served" || bad "inventory-service Swagger UI"
[ "$(status http://localhost:8091/internal)" = "403" ] && ok "inventory-service denies paths outside its API" || bad "inventory-service /internal not denied"
[ "$(status -X POST http://localhost:9091/actuator/refresh)" = "403" ] && ok "client refresh endpoint is not open over HTTP" || bad "client /actuator/refresh is open"
for url in "http://localhost:8092/api/v1/pricing/nope" "http://localhost:8094/nope" "http://localhost:8095/nope"; do
  ctype=$(curl -s -m 5 -o /dev/null -w '%{content_type}' "$url")
  [ "$ctype" = "application/problem+json" ] && ok "$url -> 404 problem+json" || bad "$url content type '$ctype'"
done
for url in "$INVENTORY" "$NODE" "$GO"; do
  frame=$(curl -s -m 5 -D - -o /dev/null "$url" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-frame-options"{print $2}')
  [ "$frame" = "DENY" ] && ok "$url sends security headers" || bad "$url X-Frame-Options='$frame'"
done
for url in "http://localhost:8091/v3/api-docs" "http://localhost:8094/v3/api-docs" "http://localhost:8095/v3/api-docs"; do
  [ -n "$(get "$url" | json openapi)" ] && ok "$url serves an OpenAPI document" || bad "$url has no OpenAPI document"
done

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED${OFF}"; else echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"; fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
