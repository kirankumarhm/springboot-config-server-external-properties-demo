#!/usr/bin/env bash
# In-cluster acceptance check for version C on Kubernetes (Floci EKS).
#
# Proves the core requirement inside a real cluster: uploading an object to S3 reaches EVERY pod -
# including both pricing-service replicas - with no restart. Individual pod IPs are queried rather
# than the Service, because a Service would load-balance and could hide a replica that never
# received the broadcast.
#
# The configuration change is made from the HOST with the aws CLI, which is the real operator path.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NS=config-demo
KC="${KUBECONFIG_OVERRIDE:-$HERE/floci-eks.kubeconfig}"

export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
BUCKET=acme-platform-config
PREFIX=main/

PASS=0
FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
ok()  { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
head2(){ echo; echo "${BOLD}$1${OFF}"; }

k()     { kubectl --kubeconfig="$KC" "$@"; }
probe() { k -n $NS exec deploy/config-server -c config-server -- wget -qO- --timeout=6 "$1" 2>/dev/null; }
pod_ips(){ k -n $NS get pods -l app="$1" -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'; }
snap_version() { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])' 2>/dev/null; }
snap_field()   { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c "import sys,json;print(json.load(sys.stdin)$3)" 2>/dev/null; }

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version C on Kubernetes (Floci EKS) - in-cluster acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

head2 "Cluster"
echo "  control plane: $(k version -o json 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])' 2>/dev/null)"
k -n $NS get pods -o wide --no-headers 2>/dev/null | awk '{printf "  %-38s %-9s %s\n",$1,$3,$6}'

INV_IPS=($(pod_ips inventory-service))
PRC_IPS=($(pod_ips pricing-service))
CFG_IPS=($(pod_ips config-server))
[ "${#CFG_IPS[@]}" -ge 2 ] && ok "config-server has ${#CFG_IPS[@]} replicas" || bad "config-server replicas=${#CFG_IPS[@]}"
[ "${#PRC_IPS[@]}" -ge 2 ] && ok "pricing-service has ${#PRC_IPS[@]} replicas" || bad "pricing replicas=${#PRC_IPS[@]}"

head2 "S3 backend reachable from inside the cluster"
# The endpoint is an IP on purpose: AwsS3EnvironmentRepositoryFactory builds its own S3Client with
# no path-style option, so a hostname would force virtual-host addressing and require
# <bucket>.<host> in cluster DNS. With an IP the SDK falls back to path-style automatically.
ep=$(k -n $NS get configmap config-server-env -o jsonpath='{.data.AWS_ENDPOINT}' 2>/dev/null)
echo "  AWS_ENDPOINT = $ep"
echo "$ep" | grep -qE 'http://[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:' \
  && ok "endpoint is an IP, so the SDK uses path-style (no per-bucket DNS needed)" \
  || bad "endpoint is not an IP: $ep"

srcs=$(probe 'http://config-admin:admin-secret@localhost:8888/inventory-service/default/main' \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("propertySources",[])))' 2>/dev/null)
[ "${srcs:-0}" -ge 2 ] 2>/dev/null && ok "Environment API serves $srcs property sources from S3" \
                                   || bad "property sources=${srcs:-0}"

head2 "Encryption: {cipher} decrypted server-side, secret never exposed"
fp=$(snap_field "${INV_IPS[0]}" 8081 "['settings']['downstreamApiKeyFingerprint']")
[ "$fp" = "sha256:9fb8b8e0e535fecf" ] && ok "client holds the decrypted secret (fingerprint $fp)" \
                                      || bad "fingerprint=$fp"
probe "http://${INV_IPS[0]}:8081/api/v1/config/snapshot" | grep -q 'ak_live_' \
  && bad "the API response leaks the secret" || ok "the secret is absent from the API response"

head2 "Management port is separate and protected"
n401=$(k -n $NS exec deploy/config-server -c config-server -- \
  sh -c 'wget -qS -O /dev/null http://localhost:9888/actuator/env 2>&1 | grep -c 401 || true' 2>/dev/null)
[ "${n401:-0}" -ge 1 ] && ok "/actuator/env requires authentication on 9888" || bad "expected 401, got none"
[ "$(probe http://localhost:9888/actuator/health | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])' 2>/dev/null)" = "UP" ] \
  && ok "probes reachable unauthenticated on 9888" || bad "health not UP on 9888"

head2 "S3 -> SQS detector is wired"
mech=$(probe http://localhost:9888/actuator/health | python3 -c "
import sys,json
print(json.load(sys.stdin).get('components',{}).get('configChange',{}).get('details',{}).get('mechanism'))" 2>/dev/null)
[ "$mech" = "S3 Event Notifications -> SQS" ] && ok "detector reports: $mech" || bad "mechanism=$mech"

head2 "THE REQUIREMENT: an S3 upload reaches every pod with no restart"
INV_BEFORE=(); PRC_BEFORE=()
for ip in "${INV_IPS[@]}"; do INV_BEFORE+=("$(snap_version "$ip" 8081)"); done
for ip in "${PRC_IPS[@]}"; do PRC_BEFORE+=("$(snap_version "$ip" 8082)"); done
echo "  baseline versions: inventory=${INV_BEFORE[*]}  pricing=${PRC_BEFORE[*]}"
restarts_before=$(k -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')

NEW_LABEL="eks-verified-$(date +%s)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/application.yml" <<EOF
demo:
  shared:
    banner-message: "Configured centrally via Spring Cloud Config - AWS S3 backend"
    environment-label: "$NEW_LABEL"
EOF
aws s3 cp "$TMP/application.yml" "s3://$BUCKET/${PREFIX}application.yml" >/dev/null 2>&1
echo "  uploaded application.yml with environment-label -> $NEW_LABEL"

deadline=$((SECONDS + 60))
while [ $SECONDS -lt $deadline ]; do
  all=1
  for i in "${!INV_IPS[@]}"; do
    v=$(snap_version "${INV_IPS[$i]}" 8081); [ -n "$v" ] && [ "$v" -gt "${INV_BEFORE[$i]}" ] 2>/dev/null || all=0
  done
  for i in "${!PRC_IPS[@]}"; do
    v=$(snap_version "${PRC_IPS[$i]}" 8082); [ -n "$v" ] && [ "$v" -gt "${PRC_BEFORE[$i]}" ] 2>/dev/null || all=0
  done
  [ "$all" -eq 1 ] && break
  sleep 2
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
restarts_after=$(k -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
[ "$restarts_before" = "$restarts_after" ] && ok "no pod restarted" || bad "restarts changed: '$restarts_before' -> '$restarts_after'"

head2 "S3 object versioning is the audit trail (replaces git log)"
nver=$(aws s3api list-object-versions --bucket "$BUCKET" --prefix "${PREFIX}application.yml" \
  --query 'length(Versions)' --output text 2>/dev/null)
[ "${nver:-0}" -ge 2 ] 2>/dev/null && ok "application.yml has $nver retained versions" || bad "versions=${nver:-0}"

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED (in-cluster, Floci EKS)${OFF}"
else
  echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"
fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
