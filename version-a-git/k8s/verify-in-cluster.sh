#!/usr/bin/env bash
# In-cluster acceptance check for version A (Git backend) on Kubernetes.
#
#   # from: version-a-git/   (deploy-minikube.sh runs this for you at the end)
#   ./k8s/verify-in-cluster.sh
#
# Proves the core requirement inside a real cluster: a configuration change committed and PUSHED
# to the Git remote reaches EVERY client pod - Spring Boot, Node.js and Go - with no restart.
#
# WARNING: this pushes two commits to the remote (the change, then its revert). It stages only
# the four config-repo files it edits; nothing else in your working tree is committed.
#
# Why a real push rather than a local commit: in a cluster the Config Server cannot use the
# file:// backend (it treats the repo directory as its working tree and performs a real
# `git checkout`, so replicas would corrupt each other). Each pod clones the REMOTE instead, so
# the remote is the only source of truth a test can change.
#
# Why busrefresh rather than the /monitor webhook: /monitor's payload-validation filter rejects
# even correctly signed payloads, so the supported trigger here is
# POST /actuator/busrefresh on the Config Server management port. That still exercises the real
# propagation path - one broadcast over Spring Cloud Bus, every client re-fetches - because the
# Config Server is configured with force-pull: true and re-pulls the remote on the next fetch.
#
# Individual pod IPs are queried rather than the Service, because a Service would load-balance and
# could hide a replica that never received the broadcast. That is exactly the failure this design
# must not have.
#
# The change is reverted with a second commit in a trap, so the remote is left as it was even if
# the script fails or is interrupted.
set -uo pipefail

NS=config-demo
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
CONFIG_DIR="$ROOT/config-repo"
REPO_ROOT="$(cd "$ROOT" && git rev-parse --show-toplevel)"
# Paths relative to the repository root: what `git add` needs, and what the Config Server's
# search-paths point inside on the remote.
rel() { python3 -c "import os,sys;print(os.path.relpath(sys.argv[1],sys.argv[2]))" "$CONFIG_DIR/$1" "$REPO_ROOT"; }

ADMIN_USER="${CONFIG_ADMIN_USERNAME:-config-admin}"
# The Secret stores the {noop}-prefixed encoded form; HTTP Basic needs the plaintext.
ADMIN_PASS="${CONFIG_ADMIN_PASSWORD_PLAIN:-admin-secret}"
CLIENT_USER="${CONFIG_CLIENT_USERNAME:-config-client}"
CLIENT_PASS="${CONFIG_CLIENT_PASSWORD:-client-secret}"

PASS=0
FAIL=0
RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

ok()  { PASS=$((PASS+1)); echo "  ${GREEN}PASS${OFF}  $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ${RED}FAIL${OFF}  $1"; }
head2(){ echo; echo "${BOLD}$1${OFF}"; }

# A tiny helper pod would need an image pull, which this minikube VM cannot do; the config-server
# image is Alpine-based so busybox wget is already there, and it can reach the clients.
probe() { kubectl -n $NS exec deploy/config-server -c config-server -- wget -qO- --timeout=5 "$1" 2>/dev/null; }

# The Config Server's app port authenticates everything except health/info, so reading the
# Environment API needs the client credentials that the clients themselves use.
probe_auth() {
  kubectl -n $NS exec deploy/config-server -c config-server -- \
    wget -qO- --timeout=10 \
    --header="Authorization: Basic $(printf '%s:%s' "$CLIENT_USER" "$CLIENT_PASS" | base64)" \
    "$1" 2>/dev/null
}

# Triggers the bus broadcast from inside the cluster, authenticated on the management port.
#
# Content-Type MUST be set explicitly: busybox wget defaults a --post-data body to
# application/x-www-form-urlencoded, and the actuator endpoint answers 415 Unsupported Media Type.
# A silent 415 looks exactly like a working broadcast that nothing acted on, so the status line is
# returned to the caller rather than discarded.
busrefresh() {
  kubectl -n $NS exec deploy/config-server -c config-server -- sh -c '
    wget -S -O- --timeout=25 --post-data="" \
      --header="Authorization: Basic '"$(printf '%s:%s' "$ADMIN_USER" "$ADMIN_PASS" | base64)"'" \
      --header="Content-Type: application/json" \
      http://localhost:9888/actuator/busrefresh 2>&1' 2>/dev/null \
    | grep -oE "HTTP/1\.1 [0-9]+" | head -1
}

pod_ips() { kubectl -n $NS get pods -l app="$1" --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.status.podIP}{" "}{end}'; }
field()   { probe "$1" | python3 -c 'import sys,json;v=json.load(sys.stdin)[sys.argv[1]];print(str(v).lower() if isinstance(v,bool) else v)' "$2" 2>/dev/null; }

# yaml_get / yaml_set <file> <key> [value] : read or replace the first "key: ..." line.
yaml_get() { python3 -c 'import re,sys
m=re.search(rf"^\s*{re.escape(sys.argv[2])}:\s*(.+)$", open(sys.argv[1]).read(), re.M); print(m.group(1).strip() if m else "")' "$CONFIG_DIR/$1" "$2"; }
yaml_set() { python3 - "$CONFIG_DIR/$1" "$2" "$3" <<'PY2'
import re, sys
path, key, value = sys.argv[1:]
text = open(path).read()
new, n = re.subn(rf'^(\s*){re.escape(key)}:.*$', lambda m: f"{m.group(1)}{key}: {value}", text, count=1, flags=re.M)
if n != 1:
    sys.exit(f"key '{key}' not found in {path}")
open(path, 'w').write(new)
PY2
}

# file | key | service | port | path | JSON field | changed value
CHANGES="inventory-service.yml|max-order-quantity|inventory-service|8081|/api/v1/inventory/config|maxOrderQuantity|750
pricing-service.yml|surge-pricing-enabled|pricing-service|8082|/api/v1/pricing/config|surgePricingEnabled|true
node-service.yml|max-items|node-service|8084|/api/v1/node/config|maxItems|30
go-service.yml|max-items|go-service|8085|/api/v1/go/config|maxItems|55"
FILES="inventory-service.yml pricing-service.yml node-service.yml go-service.yml"

# push <message> : commits ONLY the four config files and pushes. Unrelated work in the working
# tree is never staged.
push() {
  local paths="" f
  for f in $FILES; do paths="$paths $(rel "$f")"; done
  (cd "$REPO_ROOT" && git add -- $paths && git commit -q -m "$1" -- $paths && git push -q origin HEAD)
}

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version A (Git backend) on Kubernetes - in-cluster acceptance${OFF}"
echo "${BOLD}==================================================================${OFF}"

head2 "Topology"
kubectl -n $NS get pods -o wide --no-headers | awk '{printf "  %-40s %-8s %s\n", $1, $3, $6}'
for svc in config-server inventory-service pricing-service node-service go-service; do
  ready=$(kubectl -n $NS get deploy "$svc" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
  [ "${ready:-0}" -ge 1 ] 2>/dev/null && ok "$svc: $ready pod(s) ready" || bad "$svc has no ready pod"
done

head2 "The Git backend is the remote, not a working tree"
uri=$(kubectl -n $NS get configmap config-server-env -o jsonpath='{.data.CONFIG_REPO_URI}')
case "$uri" in
  file:*|"") bad "CONFIG_REPO_URI is '$uri' - file:// cannot run in a cluster" ;;
  *)         ok "CONFIG_REPO_URI is a remote ($uri)" ;;
esac
remote_has=$(probe_auth "http://localhost:8888/node-service/default/main" | grep -c 'node-service.yml')
[ "${remote_has:-0}" -ge 1 ] && ok "the remote holds node-service.yml (pushed)" \
  || bad "the remote has no node-service.yml - commit and push version-a-git/config-repo first"

head2 "Management port is separate and protected"
code=$(kubectl -n $NS exec deploy/config-server -c config-server -- \
  sh -c 'wget -qS -O /dev/null http://localhost:9888/actuator/env 2>&1 | grep -c "401" || true' 2>/dev/null)
[ "${code:-0}" -ge 1 ] && ok "/actuator/env on the management port requires authentication" || bad "/actuator/env was not 401"

head2 "THE REQUIREMENT: a pushed Git change reaches every pod of its service, with no restart"
ORIGINAL=""
while IFS='|' read -r file key _; do ORIGINAL="$ORIGINAL$file|$key|$(yaml_get "$file" "$key")
"; done <<< "$CHANGES"

# Restore the remote however this script exits, then broadcast so the pods match it again.
restore() {
  echo; echo "  restoring the original values on the remote"
  while IFS='|' read -r file key value; do [ -n "$file" ] && yaml_set "$file" "$key" "$value"; done <<< "$ORIGINAL"
  if push "test: restore values after k8s in-cluster verification"; then
    busrefresh >/dev/null 2>&1; echo "  remote restored and pods refreshed"
  else
    echo "  ${RED}WARNING${OFF} could not restore the remote - check version-a-git/config-repo manually"
  fi
}
trap restore EXIT

restarts_before=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
while IFS='|' read -r file key _ _ _ _ value; do yaml_set "$file" "$key" "$value"; done <<< "$CHANGES"
if ! push "test: change one value per service for k8s in-cluster verification"; then
  bad "could not commit and push the test change"; exit 1
fi
status=$(busrefresh)
[ "$status" = "HTTP/1.1 200" ] && ok "POST /actuator/busrefresh accepted ($status)" || bad "busrefresh returned '${status:-nothing}'"

while IFS='|' read -r _ _ svc port path json value; do
  for ip in $(pod_ips "$svc"); do
    got=""; deadline=$((SECONDS + 40))
    while [ $SECONDS -lt $deadline ]; do
      got=$(field "http://$ip:$port$path" "$json"); [ "$got" = "$value" ] && break; sleep 1
    done
    [ "$got" = "$value" ] && ok "$svc pod $ip: $json -> $value" || bad "$svc pod $ip: $json=$got, expected $value"
  done
done <<< "$CHANGES"
restarts_after=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
[ "$restarts_before" = "$restarts_after" ] && ok "no pod restarted (restart counts unchanged)" \
                                           || bad "a pod restarted: '$restarts_before' -> '$restarts_after'"

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED (in-cluster)${OFF}"; else echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"; fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
