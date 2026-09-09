#!/usr/bin/env bash
# End-to-end acceptance test for version A (Git backend).
#
# Proves the core requirement: a property changed in the external config repository reaches every
# running client within seconds, with no restart. Each check maps to an acceptance criterion in
# ../../REQUIREMENTS.md.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$HERE/../config-repo"

INV="http://localhost:8081"
PRC="http://localhost:8082"
PRC2="http://localhost:8083"
INV_MGMT="http://localhost:9081"
SERVER="http://localhost:8888"
ADMIN="config-admin:admin-secret"
# The Config Server now exposes actuator on a SEPARATE port (management child context).
SERVER_MGMT="http://localhost:9898"

SLA_SECONDS=5

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

SECRET_LOG_CONTAINER="cfg-git-inventory"
check_store_has_no_plaintext() { grep -rl "ak_live_3f9c2b7e41d84a6f" "$REPO" 2>/dev/null; }

# Waits until an application's snapshot version exceeds a baseline. Prints elapsed seconds.
wait_for_version_above() {
  local url="$1" baseline="$2" deadline elapsed start now v
  start=$(python3 -c 'import time;print(time.time())')
  deadline=$((SECONDS + SLA_SECONDS))
  while [ $SECONDS -lt $deadline ]; do
    v=$(version "$url")
    if [ -n "$v" ] && [ "$v" -gt "$baseline" ] 2>/dev/null; then
      now=$(python3 -c 'import time;print(time.time())')
      elapsed=$(python3 -c "print(f'{$now-$start:.2f}')")
      echo "$elapsed"
      return 0
    fi
    sleep 0.2
  done
  echo "TIMEOUT"
  return 1
}

# Polls until lastOutcome equals one of the expected values. Fixed sleeps are unreliable here:
# propagation is normally sub-second but has been observed at ~3.5s when the Config Server has
# to re-checkout the Git working tree.
wait_for_outcome() { # url, timeout, expected...
  local url="$1" timeout="$2"; shift 2
  local deadline=$((SECONDS + timeout)) o
  while [ $SECONDS -lt $deadline ]; do
    o=$(field "$url" lastOutcome)
    for want in "$@"; do [ "$o" = "$want" ] && { echo "$o"; return 0; }; done
    sleep 0.3
  done
  echo "${o:-unknown}"
  return 1
}

commit_config() {
  git -C "$REPO" add -A
  if git -C "$REPO" diff --cached --quiet; then
    echo "  ${YELLOW}note${OFF}  nothing to commit for: $1"
    return 1
  fi
  git -C "$REPO" commit -q -m "$1"
}

# The suite mutates the config repository, so it must not assume a pristine checkout.
# Restore a known baseline first, otherwise a second run writes identical values, git has
# nothing to commit, no hook fires, and the refresh assertions fail for the wrong reason.
reset_baseline() {
  set_yaml inventory-service.yml express-shipping-enabled false
  set_yaml inventory-service.yml max-order-quantity 500
  set_yaml application.yml environment-label '"local"'
  if commit_config "Reset configuration to test baseline"; then
    sleep 3
    echo "  baseline restored and propagated"
  else
    echo "  already at baseline"
  fi
}

set_yaml() { # file, key, value
  local f="$REPO/$1"
  python3 - "$f" "$2" "$3" <<'PY'
import re, sys
path, key, value = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path).read()
new, n = re.subn(rf'^(\s*){re.escape(key)}:.*$', lambda m: f"{m.group(1)}{key}: {value}", text,
                 count=1, flags=re.MULTILINE)
if n != 1:
    sys.exit(f"could not find key '{key}' in {path}")
open(path, 'w').write(new)
PY
}

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version A (Git backend) - end-to-end acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

# ---------------------------------------------------------------- preconditions
head2 "Preconditions"
for pair in "config-server:$SERVER_MGMT/actuator/health" \
            "inventory-service:$INV_MGMT/actuator/health" \
            "pricing-service:http://localhost:9082/actuator/health" \
            "pricing-service-2:http://localhost:9083/actuator/health"; do
  name="${pair%%:*}"; url="${pair#*:}"
  status=$(curl -s -m 5 "$url" | jget status)
  if [ "$status" = "UP" ]; then ok "$name is UP"; else bad "$name health = ${status:-unreachable}"; fi
done

if [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$ADMIN" "$SERVER/inventory-service/default")" = "200" ]; then
  ok "Environment API serves inventory-service/default (authenticated)"
else
  bad "Environment API not serving inventory-service/default"
fi

if [ "$(curl -s -o /dev/null -w '%{http_code}' "$SERVER/inventory-service/default")" = "401" ]; then
  ok "Environment API rejects unauthenticated access (NFR-10)"
else
  bad "Environment API is NOT protected"
fi

head2 "Baseline"
reset_baseline

# ---------------------------------------------------------------- AC-04 + AC-02
head2 "AC-02 / AC-04  scoped refresh + feature flag takes effect live"
inv_before=$(version "$INV"); prc_before=$(version "$PRC")
echo "  baseline versions: inventory=$inv_before pricing=$prc_before"
mode_before=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)
echo "  shippingMode before: $mode_before"

set_yaml inventory-service.yml express-shipping-enabled true
commit_config "Enable express shipping for inventory-service"

elapsed=$(wait_for_version_above "$INV" "$inv_before")
if [ "$elapsed" != "TIMEOUT" ]; then
  ok "inventory-service refreshed in ${elapsed}s (SLA ${SLA_SECONDS}s)"
else
  bad "inventory-service did not refresh within ${SLA_SECONDS}s"
fi

flag_after=$(field "$INV" settings.expressShippingEnabled)
[ "$flag_after" = "True" ] && ok "expressShippingEnabled is now true (no restart)" \
                           || bad "expressShippingEnabled = $flag_after (expected True)"

mode_after=$(curl -s -m 5 -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1","quantity":10}' | jget shippingMode)
[ "$mode_after" = "EXPRESS" ] && ok "business behaviour changed: shippingMode $mode_before -> EXPRESS" \
                              || bad "shippingMode = $mode_after (expected EXPRESS)"

prc_after=$(version "$PRC")
[ "$prc_after" = "$prc_before" ] && ok "pricing-service untouched by a scoped change (version $prc_after)" \
                                 || bad "pricing-service version moved $prc_before -> $prc_after"

# ---------------------------------------------------------------- AC-01 + AC-03
head2 "AC-01 / AC-03  shared change reaches ALL apps and ALL instances"
inv_b=$(version "$INV"); prc_b=$(version "$PRC"); prc2_b=$(version "$PRC2")
echo "  baseline: inventory=$inv_b pricing=$prc_b pricing-2=$prc2_b"

set_yaml application.yml environment-label "\"production-like\""
commit_config "Change shared environment-label for all services"

e1=$(wait_for_version_above "$INV" "$inv_b")
e2=$(wait_for_version_above "$PRC" "$prc_b")
e3=$(wait_for_version_above "$PRC2" "$prc2_b")
[ "$e1" != "TIMEOUT" ] && ok "inventory-service refreshed in ${e1}s"   || bad "inventory-service timed out"
[ "$e2" != "TIMEOUT" ] && ok "pricing-service refreshed in ${e2}s"     || bad "pricing-service timed out"
[ "$e3" != "TIMEOUT" ] && ok "pricing-service-2 refreshed in ${e3}s (one broadcast, every instance)" \
                       || bad "pricing-service-2 timed out"

for u in "$INV" "$PRC" "$PRC2"; do
  lbl=$(field "$u" settings.environmentLabel)
  [ "$lbl" = "production-like" ] && ok "$u sees environmentLabel=production-like" \
                                 || bad "$u environmentLabel=$lbl"
done

# ---------------------------------------------------------------- AC-05
head2 "AC-05  invalid configuration is rejected, last-known-good retained"
good_version=$(version "$INV")
good_max=$(field "$INV" settings.maxOrderQuantity)
echo "  known-good: version=$good_version maxOrderQuantity=$good_max"

set_yaml inventory-service.yml max-order-quantity 99999   # violates @Max(10000)
commit_config "Set an INVALID max-order-quantity to prove rejection"
wait_for_outcome "$INV" 15 REJECTED >/dev/null

after_version=$(version "$INV")
after_max=$(field "$INV" settings.maxOrderQuantity)
outcome=$(field "$INV" lastOutcome)
rejected=$(field "$INV" rejectedCount)

[ "$after_max" = "$good_max" ] && ok "still serving last-known-good maxOrderQuantity=$after_max" \
                               || bad "adopted invalid value: $after_max"
[ "$after_version" = "$good_version" ] && ok "snapshot version unchanged ($after_version)" \
                                       || bad "version moved to $after_version despite invalid config"
[ "$outcome" = "REJECTED" ] && ok "lastOutcome=REJECTED is observable" || bad "lastOutcome=$outcome"
[ "${rejected:-0}" -ge 1 ] 2>/dev/null && ok "rejectedCount=$rejected" || bad "rejectedCount=$rejected"

reason=$(field "$INV" lastFailureReason)
[ -n "$reason" ] && ok "failure reason surfaced: $reason" || bad "no failure reason recorded"

hstatus=$(curl -s -m 5 "$INV_MGMT/actuator/health" | jget status)
[ "$hstatus" = "UP" ] && ok "service still UP while serving last-known-good (correct: config is valid)" \
                      || bad "health=$hstatus"

still_serving=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$INV/api/v1/inventory/reservations" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-9","quantity":5}')
[ "$still_serving" = "200" ] && ok "business endpoint still serving traffic (HTTP 200)" \
                             || bad "business endpoint returned $still_serving"

echo "  restoring a valid value..."
set_yaml inventory-service.yml max-order-quantity "$good_max"
commit_config "Restore valid max-order-quantity"
recovered=$(wait_for_outcome "$INV" 15 NO_CHANGE APPLIED)
if [ "$recovered" = "NO_CHANGE" ] || [ "$recovered" = "APPLIED" ]; then
  ok "recovered after fixing the value (lastOutcome=$recovered)"
else
  bad "did not recover within 15s, lastOutcome=$recovered"
fi

# ---------------------------------------------------------------- FR-15
head2 "FR-15  a refresh that changes nothing is a no-op"
v_before=$(version "$INV")
curl -s -m 10 -X POST "$INV_MGMT/actuator/refresh" >/dev/null
sleep 2
v_after=$(version "$INV")
outcome=$(field "$INV" lastOutcome)
[ "$v_after" = "$v_before" ] && ok "version stayed $v_after on a no-change refresh" \
                             || bad "version moved $v_before -> $v_after"
[ "$outcome" = "NO_CHANGE" ] && ok "lastOutcome=NO_CHANGE" || bad "lastOutcome=$outcome"

# ---------------------------------------------------------------- FR-31 / AC-09
head2 "FR-31 / AC-09  audit records keys but never values"
hist=$(curl -s -m 5 "$INV/api/v1/config/history")
n=$(echo "$hist" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null)
[ "${n:-0}" -ge 2 ] 2>/dev/null && ok "audit history has $n records" || bad "audit history has ${n:-0} records"

if echo "$hist" | grep -q '"changedKeys"'; then ok "audit records changedKeys"; else bad "no changedKeys in audit"; fi
if echo "$hist" | grep -qE '99999|WH-BLR-01|production-like'; then
  bad "audit log LEAKS property values"
else
  ok "audit contains no property values (key names only)"
fi

# ---------------------------------------------------------------- summary
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
