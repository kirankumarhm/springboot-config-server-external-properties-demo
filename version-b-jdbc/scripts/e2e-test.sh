#!/usr/bin/env bash
# End-to-end acceptance test for version B (PostgreSQL/JDBC backend).
#
# The decisive difference from version A: configuration changes are made with plain SQL, executed
# directly against the database with psql. No application code participates in the write, which is
# what proves the trigger + LISTEN/NOTIFY path catches ANY writer.
set -uo pipefail

INV="http://localhost:8091"
PRC="http://localhost:8092"
PRC2="http://localhost:8093"
INV_MGMT="http://localhost:9091"
SERVER="http://localhost:8898"
ADMIN="config-admin:admin-secret"
# The Config Server now exposes actuator on a SEPARATE port (management child context).
SERVER_MGMT="http://localhost:9899"
PG="cfg-jdbc-postgres"

SLA_SECONDS=8

PASS=0
FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

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


sql()  { docker exec -i "$PG" psql -U config_admin -d configdb -tAc "$1" 2>&1; }

set_prop() { # application, key, value
  sql "UPDATE properties SET \"value\"='$3', updated_at=now() WHERE application='$1' AND \"key\"='$2';" >/dev/null
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

reset_baseline() {
  set_prop inventory-service inventory.express-shipping-enabled false
  set_prop inventory-service inventory.max-order-quantity 500
  set_prop application demo.shared.environment-label local
  # Reset every key the suite mutates. Missing one leaks state between runs: set_prop always
  # bumps updated_at, so the trigger fires and a refresh happens, but if the VALUE is unchanged
  # the provider correctly reports NO_CHANGE and does not bump the version - which then looks
  # like a lost change to a version-based assertion.
  set_prop inventory-service inventory.low-stock-threshold 25
  sleep 3
  echo "  baseline restored"
}

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version B (PostgreSQL/JDBC backend) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ---------------------------------------------------------------- preconditions
head2 "Preconditions"
for pair in "config-server:$SERVER_MGMT/actuator/health" \
            "inventory-service:$INV_MGMT/actuator/health" \
            "pricing-service:http://localhost:9092/actuator/health" \
            "pricing-service-2:http://localhost:9093/actuator/health"; do
  name="${pair%%:*}"; url="${pair#*:}"
  status=$(curl -s -m 5 "$url" | jget status)
  [ "$status" = "UP" ] && ok "$name is UP" || bad "$name health = ${status:-unreachable}"
done

rows=$(sql "SELECT count(*) FROM properties;")
[ "${rows:-0}" -ge 10 ] 2>/dev/null && ok "Flyway seeded $rows property rows" || bad "properties table has ${rows:-0} rows"

trig=$(sql "SELECT count(*) FROM information_schema.triggers WHERE trigger_name LIKE 'trg_notify_config%';")
[ "${trig:-0}" -ge 3 ] 2>/dev/null && ok "notify triggers installed ($trig statement-level)" || bad "notify triggers missing (found ${trig:-0})"

nulls=$(sql "SELECT count(*) FROM properties WHERE profile IS NULL;")
[ "${nulls:-0}" -ge 10 ] 2>/dev/null && ok "profile-independent rows use real NULL ($nulls rows)" || bad "profile NULL rows = ${nulls:-0}"

listener=$(curl -s -m 5 "$SERVER_MGMT/actuator/health" | python3 -c "
import sys,json
d=json.load(sys.stdin)
c=d.get('components',{}).get('configChange',{}).get('details',{})
print(c.get('listenerConnected'))" 2>/dev/null)
[ "$listener" = "True" ] && ok "PostgreSQL LISTEN/NOTIFY listener is connected" || bad "listenerConnected=$listener"

if [ "$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/inventory-service/default")" = "401" ]; then
  ok "Environment API rejects unauthenticated access"
else
  bad "Environment API is NOT protected"
fi

head2 "Baseline"
reset_baseline

# ---------------------------------------------------------------- AC-13
head2 "AC-13  a plain SQL UPDATE propagates - no application involved in the write"
inv_b=$(version "$INV"); prc_b=$(version "$PRC")
echo "  baseline versions: inventory=$inv_b pricing=$prc_b"
mode_before=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)

set_prop inventory-service inventory.express-shipping-enabled true

elapsed=$(wait_for_version_above "$INV" "$inv_b")
[ "$elapsed" != "TIMEOUT" ] && ok "inventory-service refreshed in ${elapsed}s after psql UPDATE" \
                            || bad "inventory-service did not refresh within ${SLA_SECONDS}s"

mode_after=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)
[ "$mode_after" = "EXPRESS" ] && ok "business behaviour changed: $mode_before -> EXPRESS" \
                              || bad "shippingMode = $mode_after"

prc_after=$(version "$PRC")
[ "$prc_after" = "$prc_b" ] && ok "pricing-service untouched - exact scoping from the APPLICATION column" \
                            || bad "pricing-service moved $prc_b -> $prc_after"

# ---------------------------------------------------------------- AC-14
head2 "AC-14  a ROLLED BACK change must not broadcast"
inv_b=$(version "$INV")
sql "BEGIN; UPDATE properties SET \"value\"='7777' WHERE application='inventory-service' AND \"key\"='inventory.max-order-quantity'; ROLLBACK;" >/dev/null
sleep 4
inv_after=$(version "$INV")
max_now=$(field "$INV" settings.maxOrderQuantity)
[ "$inv_after" = "$inv_b" ] && ok "no refresh occurred (version stayed $inv_after) - NOTIFY is transactional" \
                            || bad "version moved $inv_b -> $inv_after on a rolled-back transaction"
[ "$max_now" = "500" ] && ok "value still 500, rolled-back data never served" || bad "maxOrderQuantity=$max_now"

# ---------------------------------------------------------------- AC-16
head2 "AC-16  a bulk UPDATE produces ONE broadcast, not one per row"
before_bcast=$(curl -s -m 5 -u "$ADMIN" "$SERVER_MGMT/actuator/metrics/config.change.broadcast" \
  | python3 -c 'import sys,json;print(int(json.load(sys.stdin)["measurements"][0]["value"]))' 2>/dev/null || echo 0)
sql "UPDATE properties SET updated_at=now() WHERE application='inventory-service';" >/dev/null
sleep 4
after_bcast=$(curl -s -m 5 -u "$ADMIN" "$SERVER_MGMT/actuator/metrics/config.change.broadcast" \
  | python3 -c 'import sys,json;print(int(json.load(sys.stdin)["measurements"][0]["value"]))' 2>/dev/null || echo 0)
delta=$((after_bcast - before_bcast))
rowcount=$(sql "SELECT count(*) FROM properties WHERE application='inventory-service';")
echo "  rows updated: $rowcount   broadcasts emitted: $delta"
[ "$delta" -eq 1 ] && ok "exactly 1 broadcast for a $rowcount-row UPDATE (statement-level trigger)" \
                   || bad "expected 1 broadcast, got $delta"

# ---------------------------------------------------------------- AC-01 / AC-03
head2 "AC-01 / AC-03  shared change reaches ALL apps and ALL instances"
inv_b=$(version "$INV"); prc_b=$(version "$PRC"); prc2_b=$(version "$PRC2")
set_prop application demo.shared.environment-label production-like
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

# ---------------------------------------------------------------- AC-05
head2 "AC-05  invalid configuration rejected, last-known-good retained"
good_v=$(version "$INV"); good_max=$(field "$INV" settings.maxOrderQuantity)
set_prop inventory-service inventory.max-order-quantity 99999
wait_for_outcome "$INV" 15 REJECTED >/dev/null
[ "$(field "$INV" settings.maxOrderQuantity)" = "$good_max" ] && ok "still serving last-known-good ($good_max)" \
                                                             || bad "adopted invalid value"
[ "$(version "$INV")" = "$good_v" ] && ok "snapshot version unchanged ($good_v)" || bad "version moved"
[ "$(field "$INV" lastOutcome)" = "REJECTED" ] && ok "lastOutcome=REJECTED observable" || bad "outcome not REJECTED"
code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"S","quantity":5}')
[ "$code" = "200" ] && ok "business endpoint still serving (HTTP 200)" || bad "endpoint returned $code"
set_prop inventory-service inventory.max-order-quantity "$good_max"
rec=$(wait_for_outcome "$INV" 15 NO_CHANGE APPLIED)
[ "$rec" = "NO_CHANGE" ] || [ "$rec" = "APPLIED" ] && ok "recovered (lastOutcome=$rec)" || bad "did not recover: $rec"

# ---------------------------------------------------------------- AC-17
head2 "AC-17  properties_history replaces git log"
hist=$(sql "SELECT count(*) FROM properties_history;")
[ "${hist:-0}" -ge 5 ] 2>/dev/null && ok "history table has $hist rows" || bad "history rows = ${hist:-0}"
detail=$(sql "SELECT operation||'|'||\"key\"||'|'||coalesce(old_value,'-')||'->'||coalesce(new_value,'-')||'|'||changed_by FROM properties_history ORDER BY history_id DESC LIMIT 1;")
echo "  latest: $detail"
echo "$detail" | grep -q 'U|inventory' && ok "records operation, key, old->new value and actor" || bad "history detail unexpected: $detail"

# ---------------------------------------------------------------- AC-15
head2 "AC-15  listener connection killed - reconciler must catch the missed change"
# Target ONLY the dedicated LISTEN session, identified by the ApplicationName the detector sets.
# An indiscriminate pg_terminate_backend also kills the main read pool, which tests nothing.
killed=$(sql "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = 'config-notify-listener';")
echo "  terminated listener sessions: $killed"
[ "${killed:-0}" -ge 1 ] 2>/dev/null && ok "the dedicated LISTEN session was identifiable and killed" \
                                     || bad "no listener session found by application_name"

# Commit a change immediately, in the window before the listener reconnects. NOTIFY is delivered
# only to sessions connected at commit time, so this payload is genuinely lost - the reconciler
# is the only thing that can still deliver it.
inv_b=$(version "$INV")
set_prop inventory-service inventory.low-stock-threshold 44

# The poller runs every 15s, so allow well past one interval.
SLA_SECONDS=40
e=$(wait_for_version_above "$INV" "$inv_b")
if [ "$e" != "TIMEOUT" ]; then
  ok "missed change still propagated in ${e}s (listener reconnect or reconciler)"
else
  bad "change permanently lost after listener kill"
fi
SLA_SECONDS=8

thr=$(field "$INV" settings.lowStockThreshold)
[ "$thr" = "44" ] && ok "new value 44 is being served" || bad "lowStockThreshold=$thr"

recon=$(curl -s -m 5 "$SERVER_MGMT/actuator/health" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('components',{}).get('configChange',{}).get('details',{}).get('listenerDrops'))" 2>/dev/null)
[ "${recon:-0}" -ge 1 ] 2>/dev/null && ok "listenerDrops=$recon recorded and visible in health" \
                                    || bad "listener drop not recorded (listenerDrops=$recon)"

h=$(curl -s -m 5 "$SERVER_MGMT/actuator/health" | jget status)
[ "$h" = "UP" ] && ok "config-server still UP (degraded notification is not an outage)" || bad "health=$h"

set_prop inventory-service inventory.low-stock-threshold 25

# ---------------------------------------------------------------- NFR-10
head2 "NFR-10  the encryption endpoints require authentication"
# No configuration value is {cipher}-encrypted any more, so there is no decrypted secret to
# assert on. The endpoints still exist, and their access control is still worth asserting.
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
