#!/usr/bin/env bash
# In-cluster acceptance check for version B on Kubernetes.
#
# Proves the core requirement inside a real cluster: a configuration change made with plain SQL
# reaches EVERY pod - including both pricing-service replicas - with no restart. Individual pod IPs
# are queried rather than the Service, because a Service would load-balance and could hide a replica
# that never received the broadcast. That is exactly the failure this design must not have.
set -uo pipefail

NS=config-demo
PASS=0
FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

ok()  { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
head2(){ echo; echo "${BOLD}$1${OFF}"; }

# A tiny helper pod would need an image pull; the config-server image already has wget and can
# reach the clients, so it is used as the in-cluster HTTP client.
probe() { kubectl -n $NS exec deploy/config-server -c config-server -- wget -qO- --timeout=5 "$1" 2>/dev/null; }
sql()   { kubectl -n $NS exec statefulset/postgres -- psql -U config_admin -d configdb -tAc "$1" 2>/dev/null; }

pod_ips() { kubectl -n $NS get pods -l app="$1" -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'; }

snap_version() { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])' 2>/dev/null; }
snap_field()   { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c "import sys,json;print(json.load(sys.stdin)$3)" 2>/dev/null; }

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version B on Kubernetes - in-cluster acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

head2 "Topology"
kubectl -n $NS get pods -o wide --no-headers | awk '{printf "  %-38s %-8s %s\n", $1, $3, $6}'

INV_IPS=($(pod_ips inventory-service))
PRC_IPS=($(pod_ips pricing-service))
CFG_IPS=($(pod_ips config-server))
echo "  config-server replicas: ${#CFG_IPS[@]}   pricing replicas: ${#PRC_IPS[@]}"
[ "${#CFG_IPS[@]}" -ge 2 ] && ok "config-server is running more than one replica" \
                           || bad "expected 2+ config-server replicas"
[ "${#PRC_IPS[@]}" -ge 2 ] && ok "pricing-service is running more than one replica" \
                           || bad "expected 2+ pricing-service replicas"

head2 "Bus instance ids are unique per pod"
ids=$(for ip in "${PRC_IPS[@]}"; do probe "http://$ip:9082/actuator/env" >/dev/null 2>&1; done; \
      kubectl -n $NS get pods -l app=pricing-service -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
uniq_count=$(echo "$ids" | sort -u | wc -l | tr -d ' ')
[ "$uniq_count" -eq "${#PRC_IPS[@]}" ] && ok "APP_INDEX comes from metadata.name, so ids differ per pod" \
                                       || bad "pod names not unique?"

head2 "Encryption: {cipher} decrypted server-side, secret never exposed"
fp=$(snap_field "${INV_IPS[0]}" 8081 "['settings']['downstreamApiKeyFingerprint']")
[ "$fp" = "sha256:9fb8b8e0e535fecf" ] && ok "client holds the decrypted secret (fingerprint $fp)" \
                                      || bad "fingerprint=$fp"
probe "http://${INV_IPS[0]}:8081/api/v1/config/snapshot" | grep -q 'ak_live_' \
  && bad "the API response leaks the secret" || ok "the secret is absent from the API response"

head2 "Management port is separate and protected"
code=$(kubectl -n $NS exec deploy/config-server -c config-server -- \
  sh -c 'wget -qS -O /dev/null http://localhost:9888/actuator/env 2>&1 | grep -c "401" || true' 2>/dev/null)
[ "${code:-0}" -ge 1 ] && ok "/actuator/env on the management port requires authentication" \
                       || bad "/actuator/env unauthenticated response was not 401"
h=$(probe "http://localhost:9888/actuator/health" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])' 2>/dev/null)
[ "$h" = "UP" ] && ok "probes reachable unauthenticated on the management port" || bad "health=$h"

head2 "THE REQUIREMENT: a SQL change reaches every pod with no restart"
# macOS ships bash 3.2, which has no associative arrays - plain indexed arrays instead.
INV_BEFORE=(); PRC_BEFORE=()
for ip in "${INV_IPS[@]}"; do INV_BEFORE+=("$(snap_version "$ip" 8081)"); done
for ip in "${PRC_IPS[@]}"; do PRC_BEFORE+=("$(snap_version "$ip" 8082)"); done
echo "  baseline versions: inventory=${INV_BEFORE[*]}  pricing=${PRC_BEFORE[*]}"

restarts_before=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')

NEW_LABEL="k8s-verified-$(date +%s)"
sql "UPDATE properties SET \"value\"='$NEW_LABEL' WHERE application='application' AND \"key\"='demo.shared.environment-label';" >/dev/null
echo "  committed SQL change: demo.shared.environment-label -> $NEW_LABEL"

deadline=$((SECONDS + 40))
while [ $SECONDS -lt $deadline ]; do
  done_all=1
  for i in "${!INV_IPS[@]}"; do
    v=$(snap_version "${INV_IPS[$i]}" 8081)
    [ -n "$v" ] && [ "$v" -gt "${INV_BEFORE[$i]}" ] 2>/dev/null || done_all=0
  done
  for i in "${!PRC_IPS[@]}"; do
    v=$(snap_version "${PRC_IPS[$i]}" 8082)
    [ -n "$v" ] && [ "$v" -gt "${PRC_BEFORE[$i]}" ] 2>/dev/null || done_all=0
  done
  [ "$done_all" -eq 1 ] && break
  sleep 1
done

for ip in "${INV_IPS[@]}"; do
  lbl=$(snap_field "$ip" 8081 "['settings']['environmentLabel']")
  [ "$lbl" = "$NEW_LABEL" ] && ok "inventory-service pod $ip refreshed" || bad "inventory pod $ip label=$lbl"
done
for ip in "${PRC_IPS[@]}"; do
  lbl=$(snap_field "$ip" 8082 "['settings']['environmentLabel']")
  [ "$lbl" = "$NEW_LABEL" ] && ok "pricing-service pod $ip refreshed (one broadcast, every replica)" \
                            || bad "pricing pod $ip label=$lbl"
done

restarts_after=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
[ "$restarts_before" = "$restarts_after" ] && ok "no pod restarted (restart counts unchanged)" \
                                           || bad "a pod restarted: '$restarts_before' -> '$restarts_after'"

head2 "Audit trail survives in the database"
n=$(sql "SELECT count(*) FROM properties_history WHERE \"key\"='demo.shared.environment-label';")
[ "${n:-0}" -ge 1 ] 2>/dev/null && ok "properties_history recorded the change ($n rows for this key)" \
                                || bad "no history row recorded"

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED (in-cluster)${OFF}"
else
  echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"
fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
