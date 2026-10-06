#!/usr/bin/env bash
# End-to-end acceptance test for version A (Git backend).
#
#   # from: version-a-git/
#   ./scripts/e2e-test.sh
#
# Proves the point of the project: change a value in the Git config repository, commit, and every
# client that owns that value serves the new value within seconds - with no restart - while the
# clients that do not own it are untouched. Covers all five clients:
#   inventory-service, pricing-service (Spring Boot)  -> refreshed over Spring Cloud Bus
#   node-service (Node.js), go-service (Go)            -> refreshed over Spring Cloud Bus
#   lambda-service (AWS Lambda in Floci)               -> reads the latest values on every call
#
# Needs: the Compose stack running against the LOCAL repo (see README section "Quick start"):
#   CONFIG_REPO_URI=file:///config-repo CONFIG_REPO_SEARCH_PATHS= CONFIG_REPO_FORCE_PULL=false \
#     docker compose -f docker/compose.yaml up -d --build
# plus lambda-service deployed to Floci (lambda-service/scripts/deploy-floci.sh), python3, curl.
# Set SKIP_LAMBDA=1 to skip the Lambda checks when Floci is not running.
#
# The suite commits to config-repo/. It sets a known baseline first and restores it at the end,
# so it can be run any number of times.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$HERE/../config-repo"
SERVER="http://localhost:8888"
SERVER_MGMT="http://localhost:9898"
CLIENT_AUTH="config-client:client-secret"
INVENTORY="http://localhost:8081/api/v1/inventory/config"
PRICING="http://localhost:8082/api/v1/pricing/config"
NODE="http://localhost:8084/api/v1/node/config"
GO="http://localhost:8085/api/v1/go/config"
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

# set_yaml <file> <key> <value> : replaces the first "key: ..." line in a config-repo file.
set_yaml() {
  python3 - "$REPO/$1" "$2" "$3" <<'PY'
import re, sys
path, key, value = sys.argv[1:]
text = open(path).read()
new, n = re.subn(rf'^(\s*){re.escape(key)}:.*$', lambda m: f"{m.group(1)}{key}: {value}", text, count=1, flags=re.M)
if n != 1:
    sys.exit(f"key '{key}' not found in {path}")
open(path, 'w').write(new)
PY
}

# commit <message> : commits config-repo; the post-commit hook tells the Config Server.
#
# Retries because the Config Server runs `git checkout` in this same directory whenever a client
# fetches configuration, and git refuses to write .git/index while the other process holds it
# ("fatal: unable to write new index file"). A short retry is the correct fix: the lock is held
# for milliseconds.
commit() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if git -C "$REPO" add -A 2>/dev/null; then
      git -C "$REPO" diff --cached --quiet && return 1
      git -C "$REPO" commit -q -m "$1" >/dev/null 2>&1 && return 0
    fi
    sleep 0.5
  done
  bad "could not commit '$1' to config-repo after 5 attempts"
  return 1
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
  set_yaml inventory-service.yml max-order-quantity 100
  set_yaml pricing-service.yml surge-pricing-enabled false
  set_yaml node-service.yml max-items 25
  set_yaml go-service.yml max-items 50
  set_yaml lambda-service.yml max-items 10
  commit "e2e: reset configuration to the test baseline" && sleep 3 || true
}
trap 'echo; echo "Restoring the baseline..."; baseline' EXIT

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version A (Git backend) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ------------------------------------------------------------------------------ preconditions
section "Preconditions"
[ "$(get "$SERVER_MGMT/actuator/health" | json status)" = "UP" ] && ok "config-server is UP" || bad "config-server is not UP"
for name in inventory:9081 pricing:9082; do
  [ "$(get "http://localhost:${name#*:}/actuator/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
for name in node:8084 go:8085; do
  [ "$(get "http://localhost:${name#*:}/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
sources=$(curl -s -m 5 -u "$CLIENT_AUTH" "$SERVER/inventory-service/default/main")
if echo "$sources" | grep -q 'file:///config-repo'; then
  ok "Config Server reads the LOCAL repo (file:///config-repo), so commits here are what it serves"
else
  bad "Config Server is NOT reading file:///config-repo - restart the stack with CONFIG_REPO_URI=file:///config-repo CONFIG_REPO_SEARCH_PATHS= CONFIG_REPO_FORCE_PULL=false"
fi
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
set_yaml inventory-service.yml max-order-quantity 750
commit "e2e: raise inventory max-order-quantity"
expect_within "inventory-service maxOrderQuantity 100 -> 750" 750 field_of "$INVENTORY" maxOrderQuantity
[ "$(field_of "$PRICING" surgePricingEnabled)" = "false" ] && ok "pricing-service untouched" || bad "pricing-service changed"
[ "$(field_of "$NODE" maxItems)" = "25" ] && ok "node-service untouched" || bad "node-service changed"

set_yaml pricing-service.yml surge-pricing-enabled true
commit "e2e: switch on surge pricing"
expect_within "pricing-service surgePricingEnabled false -> true" true field_of "$PRICING" surgePricingEnabled
[ "$(field_of "$INVENTORY" maxOrderQuantity)" = "750" ] && ok "inventory-service untouched" || bad "inventory-service changed"

set_yaml node-service.yml max-items 30
commit "e2e: change node-service max-items"
expect_within "node-service (Node.js, over Spring Cloud Bus) maxItems 25 -> 30" 30 field_of "$NODE" maxItems
[ "$(field_of "$GO" maxItems)" = "50" ] && ok "go-service untouched" || bad "go-service changed"

set_yaml go-service.yml max-items 55
commit "e2e: change go-service max-items"
expect_within "go-service (Go, over Spring Cloud Bus) maxItems 50 -> 55" 55 field_of "$GO" maxItems
[ "$(field_of "$NODE" maxItems)" = "30" ] && ok "node-service untouched" || bad "node-service changed"

if [ "$SKIP_LAMBDA" != "1" ]; then
  set_yaml lambda-service.yml max-items 12
  commit "e2e: change lambda-service max-items"
  expect_within "lambda-service (reads on every call) maxItems 10 -> 12" 12 lambda_field maxItems
fi

# ------------------------------------------------------------------------------ security and errors
section "Security and error format"
[ "$(status "$SERVER/inventory-service/default")" = "401" ] && ok "Config Server rejects unauthenticated reads" || bad "Config Server readable without credentials"
[ "$(status -H 'Content-Type: text/plain' --data-binary x "$SERVER/encrypt")" = "401" ] && ok "/encrypt requires authentication" || bad "/encrypt is open"
[ "$(status http://localhost:8081/swagger-ui/index.html)" = "200" ] && ok "inventory-service Swagger UI is served" || bad "inventory-service Swagger UI"
[ "$(status http://localhost:8081/internal)" = "403" ] && ok "inventory-service denies paths outside its API" || bad "inventory-service /internal not denied"
[ "$(status -X POST http://localhost:9081/actuator/refresh)" = "403" ] && ok "client refresh endpoint is not open over HTTP" || bad "client /actuator/refresh is open"
for url in "http://localhost:8082/api/v1/pricing/nope" "http://localhost:8084/nope" "http://localhost:8085/nope"; do
  ctype=$(curl -s -m 5 -o /dev/null -w '%{content_type}' "$url")
  [ "$ctype" = "application/problem+json" ] && ok "$url -> 404 problem+json" || bad "$url content type '$ctype'"
done
for url in "$INVENTORY" "$NODE" "$GO"; do
  frame=$(curl -s -m 5 -D - -o /dev/null "$url" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-frame-options"{print $2}')
  [ "$frame" = "DENY" ] && ok "$url sends security headers" || bad "$url X-Frame-Options='$frame'"
done
for url in "http://localhost:8081/v3/api-docs" "http://localhost:8084/v3/api-docs" "http://localhost:8085/v3/api-docs"; do
  [ -n "$(get "$url" | json openapi)" ] && ok "$url serves an OpenAPI document" || bad "$url has no OpenAPI document"
done

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED${OFF}"; else echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"; fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
