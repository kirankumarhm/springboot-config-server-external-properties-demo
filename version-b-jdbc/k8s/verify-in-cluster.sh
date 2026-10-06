#!/usr/bin/env bash
# In-cluster acceptance check for version B on Kubernetes.
#
#   # from: version-b-jdbc/   (deploy-minikube.sh runs this for you at the end)
#   ./k8s/verify-in-cluster.sh
#
# Proves the core requirement inside a real cluster: a configuration change made with plain SQL
# reaches EVERY client pod - Spring Boot, Node.js and Go - with no restart, and only the service
# that owns the changed value is affected. Each pod is queried by its own IP rather than through
# its Service, because a Service load-balances and could hide a pod that missed the broadcast.
set -uo pipefail

NS=config-demo
PASS=0; FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
ok()      { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad()     { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
section() { echo; echo "${BOLD}$1${OFF}"; }

# The config-server image already has wget and can reach every pod, so it is the in-cluster
# HTTP client - no extra helper image to pull.
probe()   { kubectl -n $NS exec deploy/config-server -c config-server -- wget -qO- --timeout=5 "$1" 2>/dev/null; }
sql()     { kubectl -n $NS exec statefulset/postgres -- psql -U config_admin -d configdb -tAc "$1" 2>/dev/null; }
pod_ips() { kubectl -n $NS get pods -l app="$1" --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.status.podIP}{" "}{end}'; }
field()   { probe "$1" | python3 -c 'import sys,json;v=json.load(sys.stdin)[sys.argv[1]];print(str(v).lower() if isinstance(v,bool) else v)' "$2" 2>/dev/null; }
set_prop() { sql "UPDATE properties SET \"value\"='$3' WHERE application='$1' AND \"key\"='$2' AND label='main' AND profile IS NULL;" >/dev/null; }

# service | port | path | property key | JSON field | baseline | changed value
CHANGES="inventory-service|8081|/api/v1/inventory/config|inventory.max-order-quantity|maxOrderQuantity|500|750
pricing-service|8082|/api/v1/pricing/config|pricing.surge-pricing-enabled|surgePricingEnabled|false|true
node-service|8084|/api/v1/node/config|node.max-items|maxItems|25|30
go-service|8085|/api/v1/go/config|go.max-items|maxItems|50|55"

restore() {
  while IFS='|' read -r svc _ _ key _ base _; do set_prop "$svc" "$key" "$base"; done <<< "$CHANGES"
}
trap 'echo; echo "Restoring the baseline..."; restore' EXIT

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version B on Kubernetes - in-cluster acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

section "Topology"
kubectl -n $NS get pods -o wide --no-headers | awk '{printf "  %-40s %-8s %s\n", $1, $3, $6}'
for svc in config-server inventory-service pricing-service node-service go-service; do
  ready=$(kubectl -n $NS get deploy "$svc" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
  [ "${ready:-0}" -ge 1 ] 2>/dev/null && ok "$svc: $ready pod(s) ready" || bad "$svc has no ready pod"
done

section "Management port is separate and protected"
code=$(kubectl -n $NS exec deploy/config-server -c config-server -- \
  sh -c 'wget -qS -O /dev/null http://localhost:9888/actuator/env 2>&1 | grep -c "401" || true' 2>/dev/null)
[ "${code:-0}" -ge 1 ] && ok "/actuator/env on the management port requires authentication" || bad "/actuator/env was not 401"

section "Baseline"
restore
sleep 5
echo "  configuration reset to the test baseline"

section "THE REQUIREMENT: a SQL change reaches every pod of its service, with no restart"
restarts_before=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
while IFS='|' read -r svc port path key json base new; do
  set_prop "$svc" "$key" "$new"
  for ip in $(pod_ips "$svc"); do
    got=""; deadline=$((SECONDS + 30))
    while [ $SECONDS -lt $deadline ]; do
      got=$(field "http://$ip:$port$path" "$json"); [ "$got" = "$new" ] && break; sleep 1
    done
    [ "$got" = "$new" ] && ok "$svc pod $ip: $json $base -> $new" || bad "$svc pod $ip: $json=$got, expected $new"
  done
done <<< "$CHANGES"   # not a pipe: a piped loop runs in a subshell and would lose the PASS/FAIL counts
restarts_after=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
[ "$restarts_before" = "$restarts_after" ] && ok "no pod restarted (restart counts unchanged)" \
                                           || bad "a pod restarted: '$restarts_before' -> '$restarts_after'"

section "Scoping: a change reaches only the service that owns it"
set_prop inventory-service inventory.max-order-quantity 600
sleep 5
for ip in $(pod_ips go-service); do
  [ "$(field "http://$ip:8085/api/v1/go/config" maxItems)" = "55" ] && ok "go-service pod $ip untouched by an inventory change" \
                                                                     || bad "go-service pod $ip changed"
done

section "Audit trail survives in the database"
n=$(sql "SELECT count(*) FROM properties_history WHERE application IN ('node-service','go-service');")
[ "${n:-0}" -ge 2 ] 2>/dev/null && ok "properties_history recorded the Node.js and Go changes ($n rows)" || bad "no history rows"

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED (in-cluster)${OFF}"; else echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"; fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
