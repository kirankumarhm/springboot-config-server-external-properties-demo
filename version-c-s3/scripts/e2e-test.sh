#!/usr/bin/env bash
# End-to-end acceptance test for version C (AWS S3 backend, on the Floci emulator).
#
#   # from: version-c-s3/
#   ./scripts/e2e-test.sh
#
# Proves the point of the project: upload a changed YAML object with `aws s3 cp`, and every
# client that owns that value serves the new value within seconds - with no restart - while the
# clients that do not own it are untouched. No application code takes part in the write: S3
# sends an event to SQS, the Config Server consumes it and broadcasts a refresh.
# Covers all five clients:
#   inventory-service, pricing-service (Spring Boot)  -> refreshed over Spring Cloud Bus
#   node-service (Node.js), go-service (Go)            -> refreshed over Spring Cloud Bus
#   lambda-service (AWS Lambda in Floci)               -> reads the latest values on every call
#
# Needs: Floci running and provisioned (scripts/provision-floci.sh), the Compose stack running
# (docker compose -f docker/compose.yaml up -d --build), lambda-service deployed to Floci
# (lambda-service/scripts/deploy-floci.sh), the AWS CLI, python3 and curl.
# Set SKIP_LAMBDA=1 to skip the Lambda checks when Floci is not running.
#
# The suite uploads objects to the bucket. It sets a known baseline first and restores it at the end, so
# it can be run any number of times.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="http://localhost:8908"
SERVER_MGMT="http://localhost:9900"
BUCKET=acme-platform-config
PREFIX=main/
export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
CLIENT_AUTH="config-client:client-secret"
INVENTORY="http://localhost:8101/api/v1/inventory/config"
PRICING="http://localhost:8102/api/v1/pricing/config"
NODE="http://localhost:8104/api/v1/node/config"
GO="http://localhost:8105/api/v1/go/config"
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

# set_prop <application> <key> <value> : downloads <application>.yml from the bucket, changes
# the key and uploads it again. One upload = one S3 event = one refresh broadcast.
set_prop() {
  local file key="${2#*.}" tmp
  file="$1.yml"; tmp="$(mktemp)"
  aws s3 cp "s3://$BUCKET/$PREFIX$file" "$tmp" >/dev/null 2>&1 || { bad "could not download $file"; return; }
  python3 - "$tmp" "$key" "$3" <<'PY2'
import re, sys
path, key, value = sys.argv[1:]
text = open(path).read()
new, n = re.subn(rf'^(\s*){re.escape(key)}:.*$', lambda m: f"{m.group(1)}{key}: {value}", text, count=1, flags=re.M)
if n != 1:
    sys.exit(f"key '{key}' not found in {path}")
open(path, 'w').write(new)
PY2
  aws s3 cp "$tmp" "s3://$BUCKET/$PREFIX$file" >/dev/null 2>&1 || bad "could not upload $file"
  rm -f "$tmp"
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
  sleep 3
}
trap 'echo; echo "Restoring the baseline..."; baseline' EXIT

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version C (S3 backend on Floci) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ------------------------------------------------------------------------------ preconditions
section "Preconditions"
[ "$(get "$SERVER_MGMT/actuator/health" | json status)" = "UP" ] && ok "config-server is UP" || bad "config-server is not UP"
for name in inventory:9101 pricing:9102; do
  [ "$(get "http://localhost:${name#*:}/actuator/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
for name in node:8104 go:8105; do
  [ "$(get "http://localhost:${name#*:}/health" | json status)" = "UP" ] \
    && ok "${name%%:*}-service is UP" || bad "${name%%:*}-service is not UP"
done
objects=$(aws s3 ls "s3://$BUCKET/$PREFIX" 2>/dev/null | wc -l | tr -d ' ')
[ "${objects:-0}" -ge 6 ] 2>/dev/null && ok "bucket holds $objects configuration objects" || bad "bucket objects = ${objects:-0} (run scripts/provision-floci.sh)"
[ "$(aws s3api get-bucket-versioning --bucket "$BUCKET" --query Status --output text 2>/dev/null)" = "Enabled" ] \
  && ok "bucket versioning is on (every change is kept, so any change can be rolled back)" || bad "bucket versioning is off"
for queue in config-change-queue config-change-dlq; do
  aws sqs get-queue-url --queue-name "$queue" >/dev/null 2>&1 && ok "SQS queue $queue exists" || bad "SQS queue $queue missing"
done
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
expect_within "inventory-service maxOrderQuantity 500 -> 750 after aws s3 cp" 750 field_of "$INVENTORY" maxOrderQuantity
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

# ------------------------------------------------------------------------------ rollback
section "Rollback: restore an earlier S3 object version"
previous=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "${PREFIX}inventory-service.yml" \
  --query 'sort_by(Versions,&LastModified)[-2].VersionId' --output text 2>/dev/null)
aws s3api copy-object --bucket "$BUCKET" --key "${PREFIX}inventory-service.yml" \
  --copy-source "$BUCKET/${PREFIX}inventory-service.yml?versionId=$previous" >/dev/null 2>&1
expect_within "inventory-service back to 500 after restoring version $previous" 500 field_of "$INVENTORY" maxOrderQuantity
events=$(get "$SERVER_MGMT/actuator/health" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["configChange"]["details"]["eventsReceived"])' 2>/dev/null)
[ "${events:-0}" -ge 1 ] 2>/dev/null && ok "Config Server health reports $events S3 events received" || bad "S3 events received: ${events:-none}"

# ------------------------------------------------------------------------------ SQS-specific guarantees
QUEUE_URL=$(aws sqs get-queue-url --queue-name config-change-queue --query QueueUrl --output text 2>/dev/null)
DLQ_URL=$(aws sqs get-queue-url --queue-name config-change-dlq --query QueueUrl --output text 2>/dev/null)

section "Duplicate S3 events are harmless (S3 delivers at least once)"
event='{"Records":[{"eventVersion":"2.1","eventSource":"aws:s3","awsRegion":"us-east-1","eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"acme-platform-config"},"object":{"key":"main/inventory-service.yml","size":127}}}]}'
before=$(field_of "$INVENTORY" maxOrderQuantity)
for n in 1 2 3; do aws sqs send-message --queue-url "$QUEUE_URL" --message-body "$event" >/dev/null 2>&1; done
sleep 6
[ "$(field_of "$INVENTORY" maxOrderQuantity)" = "$before" ] && ok "3 duplicate events: inventory-service still serves $before" \
                                                          || bad "duplicate events changed the served value"
[ "$(get "$SERVER_MGMT/actuator/health" | json status)" = "UP" ] && ok "Config Server stays UP" || bad "Config Server not UP"

section "A malformed message goes to the dead-letter queue and does not block real changes"
aws sqs purge-queue --queue-url "$DLQ_URL" >/dev/null 2>&1
aws sqs send-message --queue-url "$QUEUE_URL" --message-body 'this-is-not-json' >/dev/null 2>&1
echo "  waiting for the redrive (visibility 30s x maxReceiveCount 3, up to 2 minutes)..."
in_dlq=0
for i in $(seq 1 24); do
  sleep 5
  in_dlq=$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null)
  [ "${in_dlq:-0}" -ge 1 ] 2>/dev/null && break
done
[ "${in_dlq:-0}" -ge 1 ] 2>/dev/null && ok "the malformed message reached the dead-letter queue after its retries" \
                                     || bad "malformed message not in the dead-letter queue after 2 minutes"
set_prop node-service node.max-items 33
expect_within "the queue still delivers real changes (node-service maxItems -> 33)" 33 field_of "$NODE" maxItems

# ------------------------------------------------------------------------------ security and errors
section "Security and error format"
[ "$(status "$SERVER/inventory-service/default")" = "401" ] && ok "Config Server rejects unauthenticated reads" || bad "Config Server readable without credentials"
[ "$(status -H 'Content-Type: text/plain' --data-binary x "$SERVER/encrypt")" = "401" ] && ok "/encrypt requires authentication" || bad "/encrypt is open"
[ "$(status http://localhost:8101/swagger-ui/index.html)" = "200" ] && ok "inventory-service Swagger UI is served" || bad "inventory-service Swagger UI"
[ "$(status http://localhost:8101/internal)" = "403" ] && ok "inventory-service denies paths outside its API" || bad "inventory-service /internal not denied"
[ "$(status -X POST http://localhost:9101/actuator/refresh)" = "403" ] && ok "client refresh endpoint is not open over HTTP" || bad "client /actuator/refresh is open"
for url in "http://localhost:8102/api/v1/pricing/nope" "http://localhost:8104/nope" "http://localhost:8105/nope"; do
  ctype=$(curl -s -m 5 -o /dev/null -w '%{content_type}' "$url")
  [ "$ctype" = "application/problem+json" ] && ok "$url -> 404 problem+json" || bad "$url content type '$ctype'"
done
for url in "$INVENTORY" "$NODE" "$GO"; do
  frame=$(curl -s -m 5 -D - -o /dev/null "$url" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-frame-options"{print $2}')
  [ "$frame" = "DENY" ] && ok "$url sends security headers" || bad "$url X-Frame-Options='$frame'"
done
for url in "http://localhost:8101/v3/api-docs" "http://localhost:8104/v3/api-docs" "http://localhost:8105/v3/api-docs"; do
  [ -n "$(get "$url" | json openapi)" ] && ok "$url serves an OpenAPI document" || bad "$url has no OpenAPI document"
done

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED${OFF}"; else echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"; fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
