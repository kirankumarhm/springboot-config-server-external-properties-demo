#!/usr/bin/env bash
# Tears down version A (Git) from a local minikube cluster.
#
# Deliberately tiered, because "teardown" means different things and the expensive one is hard to
# undo. By default this removes ONLY the config-demo namespace: the next deploy then skips the
# Maven build, the image build and the image load, so a redeploy is fast. Reach for --images or
# --delete-cluster only when you actually want to pay to rebuild.
#
# Nothing here touches the Git remote. Configuration lives in GitHub, so tearing the cluster down
# cannot lose it - and a leftover test value in the repo must be reverted with a commit, not here.
set -uo pipefail

NS=config-demo
RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

DO_IMAGES=0
DO_STOP=0
DO_DELETE=0
DO_JARS=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./k8s/teardown-minikube.sh [options]

With no options: deletes the config-demo namespace only (pods, services, configmaps, secrets).
The cluster keeps running and the loaded images stay, so the next deploy is fast.

Options:
  --images          Also remove the four loaded images from the minikube VM.
                    The next deploy must rebuild and re-load them (slow).
  --jars            Also run `mvn clean` to delete local target/ directories.
  --stop            Also stop the minikube VM. Keeps the VM on disk; `minikube start` resumes it.
  --delete-cluster  Also DELETE the minikube VM entirely. Everything must be rebuilt from scratch.
  --all             Same as --images --jars --stop
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./k8s/teardown-minikube.sh                   # free the namespace, redeploy quickly later
  ./k8s/teardown-minikube.sh --stop            # done for the day, reclaim laptop RAM
  ./k8s/teardown-minikube.sh --delete-cluster  # start completely clean next time
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --images)         DO_IMAGES=1 ;;
    --jars)           DO_JARS=1 ;;
    --stop)           DO_STOP=1 ;;
    --delete-cluster) DO_DELETE=1 ;;
    --all)            DO_IMAGES=1; DO_JARS=1; DO_STOP=1 ;;
    -y|--yes)         ASSUME_YES=1 ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; echo; usage >&2; exit 2 ;;
  esac
  shift
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."

command -v kubectl >/dev/null || { echo "kubectl not installed" >&2; exit 1; }

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it. --delete-cluster in particular is
# easy to type by accident and costs a full rebuild.
# --------------------------------------------------------------------------------------------
echo "${BOLD}This will:${OFF}"
if kubectl get namespace "$NS" >/dev/null 2>&1; then
  pods=$(kubectl -n $NS get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
  echo "  - delete namespace '$NS' ($pods pod(s) running)"
else
  echo "  - ${YEL}namespace '$NS' does not exist - nothing to delete there${OFF}"
fi
[ "$DO_IMAGES" -eq 1 ] && echo "  - remove the 4 loaded images from the minikube VM (next deploy rebuilds them)"
[ "$DO_JARS"   -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
[ "$DO_STOP"   -eq 1 ] && echo "  - stop the minikube VM (resumable with 'minikube start')"
[ "$DO_DELETE" -eq 1 ] && echo "  - ${RED}DELETE the minikube VM entirely - everything must be rebuilt${OFF}"
echo "  - leave the Git remote untouched"
echo

if [ "$ASSUME_YES" -eq 0 ]; then
  printf "Continue? [y/N] "
  read -r reply
  case "$reply" in
    y|Y|yes|YES) ;;
    *) echo "aborted"; exit 0 ;;
  esac
fi

# --------------------------------------------------------------------------------------------
# Port-forwards outlive the pods they point at and then fail confusingly on the next deploy
# ("address already in use"), so clear ours first. Matched narrowly on the namespace to avoid
# killing an unrelated port-forward.
# --------------------------------------------------------------------------------------------
echo "==> Closing any port-forwards for $NS"
if pkill -f "kubectl.*port-forward.*$NS" 2>/dev/null; then
  echo "    closed"
else
  echo "    none were open"
fi

echo "==> Deleting namespace '$NS'"
if kubectl get namespace "$NS" >/dev/null 2>&1; then
  # A namespace delete blocks until every object inside is gone. --timeout keeps a stuck finalizer
  # from hanging this script forever; the warning below tells you how to look into it.
  if kubectl delete namespace "$NS" --timeout=180s; then
    echo "    ${GREEN}deleted${OFF}"
  else
    echo "    ${YEL}delete did not finish within 180s${OFF}"
    echo "    inspect with: kubectl get namespace $NS -o yaml   (look at spec.finalizers)"
  fi
else
  echo "    already gone"
fi

if [ "$DO_IMAGES" -eq 1 ]; then
  echo "==> Removing loaded images from the minikube VM"
  for img in config-git-demo-config-server:latest \
             config-git-demo-inventory-service:latest \
             config-git-demo-pricing-service:latest \
             rabbitmq:4-management; do
    printf '    %-45s' "$img"
    minikube image rm "$img" >/dev/null 2>&1 && echo "removed" || echo "not present"
  done
fi

if [ "$DO_JARS" -eq 1 ]; then
  echo "==> Deleting local build output"
  # -o (offline) because `clean` needs no network; without it this can stall on dependency checks.
  (cd "$ROOT" && mvn -B -q -o clean) && echo "    ${GREEN}target/ removed${OFF}" \
    || echo "    ${YEL}mvn clean failed - remove */target manually${OFF}"
fi

if [ "$DO_DELETE" -eq 1 ]; then
  echo "==> Deleting the minikube VM"
  minikube delete && echo "    ${GREEN}cluster deleted${OFF}"
elif [ "$DO_STOP" -eq 1 ]; then
  echo "==> Stopping the minikube VM"
  minikube stop && echo "    ${GREEN}stopped${OFF} (resume with: minikube start)"
fi

echo
echo "${BOLD}==> Remaining state${OFF}"
if minikube status >/dev/null 2>&1; then
  echo "  cluster:   running"
  echo "  namespace: $(kubectl get namespace $NS >/dev/null 2>&1 && echo present || echo gone)"
  echo "  images:    $(minikube image ls 2>/dev/null | grep -c -E 'config-git-demo|rabbitmq:4-management') of 4 still loaded"
else
  echo "  cluster:   not running"
fi

# A test value left in the repo is invisible once the cluster is gone, and the next deploy would
# silently serve it as if it were the real default.
LBL=$(grep -oE 'environment-label:[[:space:]]*"?[^"]*' "$ROOT/config-repo/application.yml" 2>/dev/null | sed 's/.*: *"*//')
case "$LBL" in
  k8s-verified-*) echo "  ${YEL}WARNING${OFF} config-repo still holds a test value: environment-label=$LBL"
                  echo "          revert it and push, or the next deploy will serve it" ;;
  *)              echo "  git repo:  environment-label=$LBL" ;;
esac
