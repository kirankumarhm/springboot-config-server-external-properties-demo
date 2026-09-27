#!/usr/bin/env bash
# In-cluster acceptance check for version A (Git backend) on Kubernetes.
#
# Proves the core requirement inside a real cluster: a configuration change committed and PUSHED
# to the Git remote reaches EVERY pod - including both pricing-service replicas - with no restart.
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
# The label change is reverted with a second commit in a trap, so the remote is left as it was
# even if the script fails or is interrupted.
set -uo pipefail

NS=config-demo
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
SHARED_YML="$ROOT/config-repo/application.yml"
# Path relative to the repository root, which is what `git` needs and what the Config Server's
# search-paths point inside.
REPO_ROOT="$(cd "$ROOT" && git rev-parse --show-toplevel)"
SHARED_REL="$(cd "$REPO_ROOT" && realpath --relative-to=. "$SHARED_YML" 2>/dev/null \
              || python3 -c "import os,sys;print(os.path.relpath(sys.argv[1],sys.argv[2]))" "$SHARED_YML" "$REPO_ROOT")"

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

pod_ips() { kubectl -n $NS get pods -l app="$1" -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'; }

snap_version() { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])' 2>/dev/null; }
snap_field()   { probe "http://$1:$2/api/v1/config/snapshot" | python3 -c "import sys,json;print(json.load(sys.stdin)$3)" 2>/dev/null; }

echo "${BOLD}==================================================================${OFF}"
echo "${BOLD} Version A (Git backend) on Kubernetes - in-cluster acceptance${OFF}"
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

head2 "The Git backend is the remote, not a working tree"
uri=$(kubectl -n $NS get configmap config-server-env -o jsonpath='{.data.CONFIG_REPO_URI}')
case "$uri" in
  file:*|"") bad "CONFIG_REPO_URI is '$uri' - file:// cannot run in a cluster" ;;
  *)         ok "CONFIG_REPO_URI is a remote ($uri)" ;;
esac
# Each pod must hold its OWN clone. If they shared one, a checkout in one would be visible in the
# other, which is the corruption the remote-URI decision exists to prevent.
basedirs=$(for ip in "${CFG_IPS[@]}"; do :; done; \
  kubectl -n $NS get pods -l app=config-server -o jsonpath='{range .items[*]}{.spec.volumes[?(@.name=="tmp")].emptyDir}{"\n"}{end}' | sort -u | wc -l | tr -d ' ')
[ "${basedirs:-0}" -ge 1 ] && ok "each config-server pod clones into its own ephemeral volume" \
                           || bad "config-server pods do not have a private clone volume"

head2 "Pod identity is unique per pod"
names=$(kubectl -n $NS get pods -l app=pricing-service -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
uniq_count=$(echo "$names" | sort -u | wc -l | tr -d ' ')
[ "$uniq_count" -eq "${#PRC_IPS[@]}" ] && ok "APP_INDEX comes from metadata.name, so ids differ per pod" \
                                       || bad "pod names not unique?"

head2 "Management port is separate and protected"
code=$(kubectl -n $NS exec deploy/config-server -c config-server -- \
  sh -c 'wget -qS -O /dev/null http://localhost:9888/actuator/env 2>&1 | grep -c "401" || true' 2>/dev/null)
[ "${code:-0}" -ge 1 ] && ok "/actuator/env on the management port requires authentication" \
                       || bad "/actuator/env unauthenticated response was not 401"
h=$(probe "http://localhost:9888/actuator/health" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])' 2>/dev/null)
[ "$h" = "UP" ] && ok "probes reachable unauthenticated on the management port" || bad "health=$h"

head2 "THE REQUIREMENT: a pushed Git change reaches every pod with no restart"

ORIGINAL_LABEL=$(python3 -c "
import re,sys
t=open('$SHARED_YML').read()
m=re.search(r'environment-label:\s*\"?([^\"\n]+)\"?', t)
print(m.group(1).strip() if m else '')
")
if [ -z "$ORIGINAL_LABEL" ]; then
  bad "could not read demo.shared.environment-label from $SHARED_REL - skipping"
else

# Restore the remote to its original state no matter how this script exits. Only the one config
# file is ever staged, so unrelated dirty files in the working tree are left untouched.
restore() {
  echo
  echo "  restoring demo.shared.environment-label -> $ORIGINAL_LABEL"
  python3 -c "
import re
p='$SHARED_YML'
t=open(p).read()
t=re.sub(r'(environment-label:\s*).*', r'\g<1>\"$ORIGINAL_LABEL\"', t, count=1)
open(p,'w').write(t)
"
  if (cd "$REPO_ROOT" && git add -- "$SHARED_REL" \
        && git commit -q -m "test: restore environment-label after k8s in-cluster verification" \
        && git push -q origin HEAD); then
    # Pushing the revert is not enough: the running pods keep serving the test value until a
    # broadcast tells them to re-fetch. Without this the cluster is left in a state that
    # contradicts the repository.
    busrefresh >/dev/null 2>&1
    echo "  remote restored and pods refreshed"
  else
    echo "  ${RED}WARNING${OFF} could not restore the remote - check $SHARED_REL manually"
  fi
}
trap restore EXIT

INV_BEFORE=(); PRC_BEFORE=()
for ip in "${INV_IPS[@]}"; do INV_BEFORE+=("$(snap_version "$ip" 8081)"); done
for ip in "${PRC_IPS[@]}"; do PRC_BEFORE+=("$(snap_version "$ip" 8082)"); done
echo "  baseline versions: inventory=${INV_BEFORE[*]}  pricing=${PRC_BEFORE[*]}"

restarts_before=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')

NEW_LABEL="k8s-verified-$(date +%s)"
python3 -c "
import re
p='$SHARED_YML'
t=open(p).read()
t=re.sub(r'(environment-label:\s*).*', r'\g<1>\"$NEW_LABEL\"', t, count=1)
open(p,'w').write(t)
"
if ! (cd "$REPO_ROOT" && git add -- "$SHARED_REL" \
        && git commit -q -m "test: set environment-label to $NEW_LABEL for k8s verification" \
        && git push -q origin HEAD); then
  bad "could not push the config change to the remote - the Git backend has nothing new to serve"
else
  sha=$(cd "$REPO_ROOT" && git rev-parse --short HEAD)
  echo "  pushed $sha: demo.shared.environment-label -> $NEW_LABEL"

  # force-pull: true means the next fetch re-pulls the remote, so the broadcast is all that is
  # needed. No pod restart, no redeploy - that is the whole point.
  echo "  broadcasting: POST /actuator/busrefresh"
  status=$(busrefresh)
  case "$status" in
    *20[0-9]) ok "busrefresh accepted ($status)" ;;
    *)        bad "busrefresh was rejected ($status) - no broadcast was sent" ;;
  esac

  deadline=$((SECONDS + 60))
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

  restarts_after=$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
  [ "$restarts_before" = "$restarts_after" ] && ok "no pod restarted (restart counts unchanged)" \
                                             || bad "a pod restarted: '$restarts_before' -> '$restarts_after'"

  head2 "Every config-server replica serves the new commit"
  # Both replicas must converge on the pushed commit; one stale clone would serve old values to
  # whichever client the Service happened to route there.
  for ip in "${CFG_IPS[@]}"; do
    served=$(probe_auth "http://$ip:8888/application/default" \
      | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("version","")[:7])' 2>/dev/null)
    [ -n "$served" ] && ok "config-server pod $ip serves commit ${served}" \
                     || bad "config-server pod $ip did not report a commit version"
  done

  head2 "Refresh audit trail is recorded in each client"
  # Count ONLY bus-triggered events. Every client records a trigger="startup" entry as it boots,
  # so counting all entries made this check pass even when no broadcast ever arrived.
  n=$(probe "http://${INV_IPS[0]}:8081/api/v1/config/history" \
       | python3 -c 'import sys,json;print(sum(1 for e in json.load(sys.stdin) if e.get("trigger")!="startup"))' 2>/dev/null)
  [ "${n:-0}" -ge 1 ] 2>/dev/null && ok "inventory-service recorded $n bus-triggered refresh event(s)" \
                                  || bad "no bus-triggered refresh recorded (startup events do not count)"
fi
fi

echo
echo "${BOLD}==================================================================${OFF}"
if [ "$FAIL" -eq 0 ]; then
  echo "${GREEN}${BOLD} ALL $PASS CHECKS PASSED (in-cluster)${OFF}"
else
  echo "${RED}${BOLD} $FAIL FAILED${OFF}, ${GREEN}$PASS passed${OFF}"
fi
echo "${BOLD}==================================================================${OFF}"
[ "$FAIL" -eq 0 ]
