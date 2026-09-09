#!/usr/bin/env bash
# End-to-end acceptance test for version C (AWS S3 backend, Floci emulator).
#
# Configuration changes are made by uploading objects to S3. Unlike Git and PostgreSQL, S3 event
# delivery is at-least-once and unordered, so this suite additionally proves the refresh path is
# idempotent and that poison messages are contained by a dead-letter queue.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

BUCKET=acme-platform-config
PREFIX=main/
QUEUE=config-change-queue
DLQ=config-change-dlq

INV="http://localhost:8101"
PRC="http://localhost:8102"
PRC2="http://localhost:8103"
INV_MGMT="http://localhost:9101"
SERVER="http://localhost:8908"
ADMIN="config-admin:admin-secret"
# The Config Server now exposes actuator on a SEPARATE port (management child context).
SERVER_MGMT="http://localhost:9900"

SLA_SECONDS=10

PASS=0
FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

jget() {
  python3 -c '
import sys, json
d = json.load(sys.stdin)
for k in sys.argv[1].split("."):
    d = d[int(k)] if k.isdigit() else d[k]
print(d)' "$1" 2>/dev/null
}

snap()    { curl -s -m 5 "$1/api/v1/config/snapshot"; }
version() { snap "$1" | jget version; }
field()   { snap "$1" | jget "$2"; }

ok()   { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
head2(){ echo; echo "${BOLD}$1${OFF}"; }

SECRET_LOG_CONTAINER="cfg-s3-inventory"
check_store_has_no_plaintext() { aws s3 cp "s3://$BUCKET/${PREFIX}inventory-service.yml" - 2>/dev/null | grep -l "ak_live_3f9c2b7e41d84a6f" 2>/dev/null; }

queue_url() { aws sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>/dev/null; }

put_config() { # local-file, object-name
  aws s3 cp "$1" "s3://$BUCKET/$PREFIX$2" >/dev/null 2>&1
}

# The encrypted key must be carried through every rewrite. This helper replaces the whole object,
# so omitting it makes downstream-api-key absent, which @NotBlank rejects - every later assertion
# then fails for the wrong reason.
CIPHER_LINE=$(grep 'downstream-api-key' "$HERE/../seed-config/inventory-service.yml")

write_inventory() { # express, maxqty, threshold
  cat > "$TMP/inventory-service.yml" <<EOF
inventory:
  warehouse-code: "WH-BLR-01"
  max-order-quantity: $2
  express-shipping-enabled: $1
  low-stock-threshold: $3
$CIPHER_LINE
EOF
  put_config "$TMP/inventory-service.yml" inventory-service.yml
}

write_shared() { # label
  cat > "$TMP/application.yml" <<EOF
demo:
  shared:
    banner-message: "Configured centrally via Spring Cloud Config - AWS S3 backend"
    environment-label: "$1"
EOF
  put_config "$TMP/application.yml" application.yml
}

wait_for_version_above() {
  local url="$1" baseline="$2" deadline start now v elapsed
  start=$(python3 -c 'import time;print(time.time())')
  deadline=$((SECONDS + SLA_SECONDS))
  while [ $SECONDS -lt $deadline ]; do
    v=$(version "$url")
    if [ -n "$v" ] && [ "$v" -gt "$baseline" ] 2>/dev/null; then
      now=$(python3 -c 'import time;print(time.time())')
      elapsed=$(python3 -c "print(f'{$now-$start:.2f}')")
      echo "$elapsed"; return 0
    fi
    sleep 0.2
  done
  echo "TIMEOUT"; return 1
}

wait_for_outcome() { # url, timeout, expected...
  local url="$1" timeout="$2"; shift 2
  local deadline=$((SECONDS + timeout)) o
  while [ $SECONDS -lt $deadline ]; do
    o=$(field "$url" lastOutcome)
    for want in "$@"; do [ "$o" = "$want" ] && { echo "$o"; return 0; }; done
    sleep 0.3
  done
  echo "${o:-unknown}"; return 1
}

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version C (AWS S3 backend via Floci) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ---------------------------------------------------------------- preconditions
head2 "Preconditions"
for pair in "config-server:$SERVER_MGMT/actuator/health" \
            "inventory-service:$INV_MGMT/actuator/health" \
            "pricing-service:http://localhost:9102/actuator/health" \
            "pricing-service-2:http://localhost:9103/actuator/health"; do
  name="${pair%%:*}"; url="${pair#*:}"
  status=$(curl -s -m 5 "$url" | jget status)
  [ "$status" = "UP" ] && ok "$name is UP" || bad "$name health = ${status:-unreachable}"
done

objs=$(aws s3 ls "s3://$BUCKET/$PREFIX" 2>/dev/null | wc -l | tr -d ' ')
[ "${objs:-0}" -ge 3 ] 2>/dev/null && ok "bucket holds $objs configuration objects" || bad "bucket objects = ${objs:-0}"

vers=$(aws s3api get-bucket-versioning --bucket "$BUCKET" --query Status --output text 2>/dev/null)
[ "$vers" = "Enabled" ] && ok "bucket versioning enabled (audit + rollback)" || bad "versioning = $vers"

[ -n "$(queue_url $QUEUE)" ] && ok "SQS queue $QUEUE exists" || bad "queue $QUEUE missing"
[ -n "$(queue_url $DLQ)" ]   && ok "SQS dead-letter queue $DLQ exists" || bad "DLQ $DLQ missing"

if [ "$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/inventory-service/default")" = "401" ]; then
  ok "Environment API rejects unauthenticated access"
else
  bad "Environment API is NOT protected"
fi

# The label MUST be included. In the S3 backend the label maps to a key PREFIX, so the bucket's
# main/ prefix IS the label "main". Requesting /inventory-service/default without a label looks
# at the bucket root, finds nothing, and correctly returns zero property sources - which looks
# like a broken server but is the documented behaviour. Clients send label=main, so they resolve.
served=$(curl -s -u "$ADMIN" "$SERVER/inventory-service/default/main" | jget 'propertySources.0.name')
[ -n "$served" ] && ok "Environment API serves from S3 with label 'main' (source: $(basename "$served"))" \
                 || bad "Environment API returned no property sources for label main"

rootless=$(curl -s -u "$ADMIN" "$SERVER/inventory-service/default" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("propertySources",[])))' 2>/dev/null)
[ "$rootless" = "0" ] && ok "no label resolves to the bucket root and returns nothing (label == key prefix)" \
                      || bad "expected 0 property sources without a label, got $rootless"

head2 "Baseline"
write_inventory false 500 25
write_shared local
sleep 4
echo "  baseline uploaded"

# ---------------------------------------------------------------- AC-18
head2 "AC-18  S3 upload propagates, scoped to one application"
inv_b=$(version "$INV"); prc_b=$(version "$PRC")
echo "  baseline versions: inventory=$inv_b pricing=$prc_b"
mode_before=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)

write_inventory true 500 25

elapsed=$(wait_for_version_above "$INV" "$inv_b")
[ "$elapsed" != "TIMEOUT" ] && ok "inventory-service refreshed in ${elapsed}s after aws s3 cp" \
                            || bad "inventory-service did not refresh within ${SLA_SECONDS}s"

mode_after=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)
[ "$mode_after" = "EXPRESS" ] && ok "business behaviour changed: $mode_before -> EXPRESS" \
                              || bad "shippingMode = $mode_after"

prc_after=$(version "$PRC")
[ "$prc_after" = "$prc_b" ] && ok "pricing-service untouched - exact key->application mapping, no dash-guessing" \
                            || bad "pricing-service moved $prc_b -> $prc_after"

# ---------------------------------------------------------------- AC-01 / AC-03
head2 "AC-01 / AC-03  shared object reaches ALL apps and ALL instances"
inv_b=$(version "$INV"); prc_b=$(version "$PRC"); prc2_b=$(version "$PRC2")
write_shared production-like
e1=$(wait_for_version_above "$INV" "$inv_b")
e2=$(wait_for_version_above "$PRC" "$prc_b")
e3=$(wait_for_version_above "$PRC2" "$prc2_b")
[ "$e1" != "TIMEOUT" ] && ok "inventory-service refreshed in ${e1}s" || bad "inventory-service timed out"
[ "$e2" != "TIMEOUT" ] && ok "pricing-service refreshed in ${e2}s"   || bad "pricing-service timed out"
[ "$e3" != "TIMEOUT" ] && ok "pricing-service-2 refreshed in ${e3}s" || bad "pricing-service-2 timed out"
for u in "$INV" "$PRC" "$PRC2"; do
  lbl=$(field "$u" settings.environmentLabel)
  [ "$lbl" = "production-like" ] && ok "$u sees production-like" || bad "$u label=$lbl"
done

# ---------------------------------------------------------------- AC-19
head2 "AC-19  duplicate events are idempotent (S3 is at-least-once)"
inv_b=$(version "$INV")
QURL=$(queue_url $QUEUE)
DUP='{"Records":[{"eventVersion":"2.1","eventSource":"aws:s3","awsRegion":"us-east-1",
"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"acme-platform-config"},
"object":{"key":"main/inventory-service.yml","size":127}}}]}'
for n in 1 2 3; do
  aws sqs send-message --queue-url "$QURL" --message-body "$DUP" >/dev/null 2>&1
done
sleep 6
inv_after=$(version "$INV")
outcome=$(field "$INV" lastOutcome)
[ "$inv_after" = "$inv_b" ] && ok "3 duplicate events -> version unchanged ($inv_after)" \
                            || bad "version moved $inv_b -> $inv_after on duplicate events"
[ "$outcome" = "NO_CHANGE" ] && ok "lastOutcome=NO_CHANGE (refresh ran, nothing adopted)" \
                             || bad "lastOutcome=$outcome"

# ---------------------------------------------------------------- AC-20
head2 "AC-20  poison message lands in the DLQ and does not block the queue"
DLQ_URL=$(queue_url $DLQ)
aws sqs purge-queue --queue-url "$DLQ_URL" >/dev/null 2>&1
sleep 2
aws sqs send-message --queue-url "$QURL" --message-body 'this-is-not-json' >/dev/null 2>&1
echo "  waiting for redrive (visibility 30s x maxReceiveCount 3)..."
dlq_count=0
for i in $(seq 1 24); do
  sleep 5
  dlq_count=$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
    --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null)
  [ "${dlq_count:-0}" -ge 1 ] 2>/dev/null && break
done
[ "${dlq_count:-0}" -ge 1 ] 2>/dev/null && ok "poison message reached the DLQ after retries" \
                                        || bad "poison message not in DLQ after 120s (count=${dlq_count:-0})"

inv_b=$(version "$INV")
write_inventory true 600 25
e=$(wait_for_version_above "$INV" "$inv_b")
[ "$e" != "TIMEOUT" ] && ok "queue still processing normally afterwards (${e}s)" \
                      || bad "queue blocked after poison message"

# ---------------------------------------------------------------- AC-05
head2 "AC-05  invalid configuration rejected, last-known-good retained"
good_v=$(version "$INV"); good_max=$(field "$INV" settings.maxOrderQuantity)
write_inventory true 99999 25
wait_for_outcome "$INV" 20 REJECTED >/dev/null
[ "$(field "$INV" settings.maxOrderQuantity)" = "$good_max" ] && ok "still serving last-known-good ($good_max)" \
                                                             || bad "adopted invalid value"
[ "$(version "$INV")" = "$good_v" ] && ok "snapshot version unchanged ($good_v)" || bad "version moved"
code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"S","quantity":5}')
[ "$code" = "200" ] && ok "business endpoint still serving (HTTP 200)" || bad "endpoint returned $code"

# ---------------------------------------------------------------- AC-21
head2 "AC-21  rollback via a prior object version (S3 versioning replaces git revert)"
nver=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "${PREFIX}inventory-service.yml" \
  --query 'length(Versions)' --output text 2>/dev/null)
[ "${nver:-0}" -ge 2 ] 2>/dev/null && ok "object has $nver versions retained" || bad "versions = ${nver:-0}"

# Second-newest version is the last VALID upload (the newest is the invalid 99999 one).
prev=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "${PREFIX}inventory-service.yml" \
  --query 'sort_by(Versions,&LastModified)[-2].VersionId' --output text 2>/dev/null)
echo "  rolling back to versionId $prev"
inv_b=$(version "$INV")
aws s3api copy-object --bucket "$BUCKET" --key "${PREFIX}inventory-service.yml" \
  --copy-source "$BUCKET/${PREFIX}inventory-service.yml?versionId=$prev" >/dev/null 2>&1
rec=$(wait_for_outcome "$INV" 20 APPLIED NO_CHANGE)
[ "$rec" = "APPLIED" ] || [ "$rec" = "NO_CHANGE" ] && ok "rollback applied (lastOutcome=$rec)" \
                                                   || bad "rollback did not apply: $rec"
maxq=$(field "$INV" settings.maxOrderQuantity)
[ "$maxq" = "600" ] && ok "rolled back to the previous valid value (600)" || bad "maxOrderQuantity=$maxq"

# ---------------------------------------------------------------- detector state
head2 "Detector observability"
det=$(curl -s -m 5 "$SERVER_MGMT/actuator/health" | python3 -c "
import sys,json
d=json.load(sys.stdin).get('components',{}).get('configChange',{}).get('details',{})
print(d.get('mechanism'),'|',d.get('eventsReceived'),'|',d.get('lastEventAt'))" 2>/dev/null)
echo "  $det"
echo "$det" | grep -q 'S3 Event Notifications' && ok "detector mechanism reported in health" || bad "detector health missing"
evt=$(echo "$det" | awk -F'|' '{print $2}' | tr -d ' ')
[ "${evt:-0}" -ge 1 ] 2>/dev/null && ok "eventsReceived=$evt" || bad "eventsReceived=${evt:-0}"

# ---------------------------------------------------------------- AC-08
head2 "AC-08  secrets are encrypted at rest and decrypted server-side"
EXPECTED_FP="sha256:9fb8b8e0e535fecf"   # sha256("ak_live_3f9c2b7e41d84a6f")[:16]

fp=$(field "$INV" settings.downstreamApiKeyFingerprint)
[ "$fp" = "$EXPECTED_FP" ] && ok "client received the DECRYPTED secret (fingerprint $fp)" \
                          || bad "fingerprint $fp != $EXPECTED_FP"

body=$(snap "$INV")
echo "$body" | grep -q 'ak_live_3f9c2b7e41d84a6f' && bad "the API response LEAKS the secret" \
                                                  || ok "the secret never appears in the API response"
echo "$body" | grep -q '{cipher}' && bad "an undecrypted {cipher} placeholder reached the client" \
                                  || ok "no undecrypted {cipher} placeholder reached the client"

logs=$(docker logs "$SECRET_LOG_CONTAINER" 2>&1 | grep -c 'ak_live_3f9c2b7e41d84a6f' || true)
[ "${logs:-0}" -eq 0 ] && ok "the secret never appears in the client logs" \
                       || bad "the secret appears in the logs $logs times"

served=$(curl -s -u "$ADMIN" "$SERVER/inventory-service/default/main" | grep -c 'ak_live_3f9c2b7e41d84a6f' || true)
[ "${served:-0}" -ge 1 ] && ok "Config Server serves it decrypted (it holds the only private key)" \
                         || bad "Config Server did not serve a decrypted value"

if [ -n "$(check_store_has_no_plaintext)" ]; then
  bad "the configuration store contains the PLAINTEXT secret"
else
  ok "the configuration store contains only ciphertext"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: text/plain' --data-binary x "$SERVER/encrypt")
[ "$code" = "401" ] && ok "/encrypt rejects unauthenticated callers" || bad "/encrypt returned $code"


echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED${OFF}"
else
  echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"
fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
