#!/usr/bin/env bash
# Tears down version C (S3 backend) from whichever cluster it was deployed to.
#
# Deliberately tiered, because "teardown" means different things and the expensive one is hard to
# undo. By default this removes ONLY the config-demo namespace: the next deploy then skips the
# Maven build, the image build and the image load, so a redeploy is fast. Reach for --images or
# --delete-cluster only when you actually want to pay to rebuild.
#
# TWO CLUSTERS, so the target is explicit rather than guessed - deleting from the wrong one leaves
# a live stack behind while reporting success:
#
#   --minikube  (default)  the local minikube VM,          deployed by deploy-minikube.sh
#   --eks                  Floci's EKS (a real k3s node),  deployed by deploy-floci-eks.sh
#
# Configuration itself lives in S3, OUTSIDE the cluster, so no teardown here can lose it - which
# is exactly why --purge-aws is a separate, explicit flag: that one does touch the source of
# truth, and only provision-floci.sh can put it back.
set -uo pipefail

NS=config-demo
CLUSTER=config-demo
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
KC="$HERE/floci-eks.kubeconfig"
MODULES="config-server inventory-service pricing-service"

RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

TARGET=minikube
DO_IMAGES=0
DO_JARS=0
DO_STOP=0
DO_DELETE=0
DO_PURGE_AWS=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./k8s/teardown.sh [--minikube | --eks] [options]

With no options: deletes the config-demo namespace from minikube only (pods, services,
configmaps, secrets). The cluster keeps running and the loaded images stay, so the next deploy
is fast. Configuration in S3 is never touched.

Target:
  --minikube        The local minikube VM (default), as deployed by deploy-minikube.sh.
  --eks             Floci's EKS cluster, as deployed by deploy-floci-eks.sh. Uses the
                    self-contained kubeconfig at k8s/floci-eks.kubeconfig.

Options:
  --images          Also remove this version's images from the target cluster's runtime.
                    On --eks that means EVERY config-s3-demo-*:1.0.0-* tag, because each deploy
                    creates a new immutable tag and they accumulate.
  --jars            Also run `mvn clean` to delete local target/ directories.
  --stop            --minikube: stop the VM (resumable). --eks: `floci stop`.
  --delete-cluster  --minikube: DELETE the VM entirely. --eks: `aws eks delete-cluster`.
                    Everything must be rebuilt from scratch.
  --purge-aws       DESTRUCTIVE and separate on purpose: empties the S3 bucket and deletes the
                    SQS queues - the configuration source of truth. Recoverable only by rerunning
                    ./scripts/provision-floci.sh, which re-seeds from seed-config/.
  --all             Same as --images --jars --stop
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./k8s/teardown.sh                          # free the namespace on minikube, redeploy quickly later
  ./k8s/teardown.sh --eks                    # same, on the Floci EKS cluster
  ./k8s/teardown.sh --eks --images           # also clear the accumulated per-deploy image tags
  ./k8s/teardown.sh --minikube --all -y      # images, jars and VM stop, no prompt
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --minikube)       TARGET=minikube ;;
    --eks)            TARGET=eks ;;
    --images)         DO_IMAGES=1 ;;
    --jars)           DO_JARS=1 ;;
    --stop)           DO_STOP=1 ;;
    --delete-cluster) DO_DELETE=1 ;;
    --purge-aws)      DO_PURGE_AWS=1 ;;
    --all)            DO_IMAGES=1; DO_JARS=1; DO_STOP=1 ;;
    -y|--yes)         ASSUME_YES=1 ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; echo; usage >&2; exit 2 ;;
  esac
  shift
done

command -v kubectl >/dev/null || { echo "kubectl not installed" >&2; exit 1; }

# One indirection for both targets, so every kubectl below is target-agnostic.
if [ "$TARGET" = eks ]; then
  [ -f "$KC" ] || { echo "no kubeconfig at $KC - was deploy-floci-eks.sh ever run?" >&2; exit 1; }
  k() { kubectl --kubeconfig="$KC" "$@"; }
  K3S="floci-eks-$CLUSTER"
else
  k() { kubectl "$@"; }
fi

# --purge-aws and --delete-cluster --eks talk to Floci, which needs its endpoint and credentials.
if [ "$DO_PURGE_AWS" -eq 1 ] || { [ "$DO_DELETE" -eq 1 ] && [ "$TARGET" = eks ]; } \
   || { [ "$DO_STOP" -eq 1 ] && [ "$TARGET" = eks ]; }; then
  export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
  export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
  export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
  export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
fi

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it. --delete-cluster and --purge-aws
# are both easy to type by accident and cost real rebuild time.
# --------------------------------------------------------------------------------------------
echo "${BOLD}Target: $TARGET${OFF}"
echo "${BOLD}This will:${OFF}"
NS_EXISTS=0
if k get namespace "$NS" >/dev/null 2>&1; then
  NS_EXISTS=1
  pods=$(k -n $NS get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
  echo "  - delete namespace '$NS' ($pods pod(s) running)"
else
  echo "  - ${YEL}namespace '$NS' does not exist on $TARGET - nothing to delete there${OFF}"
fi
[ "$DO_IMAGES" -eq 1 ] && echo "  - remove this version's images from the $TARGET runtime (next deploy rebuilds them)"
[ "$DO_JARS"   -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
if [ "$DO_STOP" -eq 1 ]; then
  [ "$TARGET" = eks ] && echo "  - stop Floci ('floci start' resumes it)" \
                      || echo "  - stop the minikube VM (resumable with 'minikube start')"
fi
if [ "$DO_DELETE" -eq 1 ]; then
  [ "$TARGET" = eks ] && echo "  - ${RED}DELETE the EKS cluster - the k3s node and its containerd images go with it${OFF}" \
                      || echo "  - ${RED}DELETE the minikube VM entirely - everything must be rebuilt${OFF}"
fi
if [ "$DO_PURGE_AWS" -eq 1 ]; then
  echo "  - ${RED}EMPTY the S3 bucket and DELETE the SQS queues - this is the configuration itself${OFF}"
  echo "    (recover with: ./scripts/provision-floci.sh)"
else
  echo "  - leave S3 and SQS untouched: configuration lives outside the cluster"
fi
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

echo "==> Deleting namespace '$NS' from $TARGET"
if [ "$NS_EXISTS" -eq 1 ]; then
  # A namespace delete blocks until every object inside is gone. --timeout keeps a stuck finalizer
  # from hanging this script forever; the warning below tells you how to look into it.
  if k delete namespace "$NS" --timeout=180s; then
    echo "    ${GREEN}deleted${OFF}"
  else
    echo "    ${YEL}delete did not finish within 180s${OFF}"
    echo "    inspect with: kubectl get namespace $NS -o yaml   (look at spec.finalizers)"
  fi
else
  echo "    already gone"
fi

if [ "$DO_IMAGES" -eq 1 ]; then
  if [ "$TARGET" = eks ]; then
    echo "==> Removing images from the k3s containerd namespace"
    # Every deploy creates a unique immutable tag (see note 4 in deploy-floci-eks.sh), so these
    # accumulate one set per deploy and must be matched by pattern, not by name.
    if docker inspect "$K3S" >/dev/null 2>&1; then
      imgs=$(docker exec "$K3S" ctr -n k8s.io images ls -q 2>/dev/null | grep 'config-s3-demo-' || true)
      if [ -z "$imgs" ]; then
        echo "    none present"
      else
        echo "$imgs" | while read -r img; do
          [ -n "$img" ] || continue
          printf '    %-60s' "$img"
          docker exec "$K3S" ctr -n k8s.io images rm "$img" >/dev/null 2>&1 && echo "removed" || echo "FAILED"
        done
      fi
      # The host daemon holds the same per-deploy tags; leaving them behind fills the disk.
      host=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^config-s3-demo-.*:1\.0\.0-' || true)
      if [ -n "$host" ]; then
        echo "    host daemon: removing $(echo "$host" | wc -l | tr -d ' ') per-deploy tag(s)"
        echo "$host" | xargs -r docker rmi >/dev/null 2>&1 || true
      fi
    else
      echo "    ${YEL}k3s node container $K3S is not running - nothing to clean${OFF}"
    fi
  else
    echo "==> Removing loaded images from the minikube VM"
    for m in $MODULES; do
      printf '    %-45s' "config-s3-demo-${m}:latest"
      minikube image rm "config-s3-demo-${m}:latest" >/dev/null 2>&1 && echo "removed" || echo "not present"
    done
    printf '    %-45s' "rabbitmq:4-management"
    minikube image rm rabbitmq:4-management >/dev/null 2>&1 && echo "removed" || echo "not present"
  fi
fi

if [ "$DO_JARS" -eq 1 ]; then
  echo "==> Deleting local build output"
  # -o (offline) because `clean` needs no network; without it this can stall on dependency checks.
  (cd "$ROOT" && mvn -B -q -o clean) && echo "    ${GREEN}target/ removed${OFF}" \
    || echo "    ${YEL}mvn clean failed - remove */target manually${OFF}"
fi

if [ "$DO_PURGE_AWS" -eq 1 ]; then
  echo "==> Purging S3 and SQS"
  BUCKET="${CONFIG_BUCKET:-acme-platform-config}"
  # Versioning is enabled, so `aws s3 rm --recursive` leaves every non-current version behind and
  # the bucket is not actually empty. Delete-markers included.
  python3 - "$BUCKET" <<'PY' || echo "    ${YEL}version sweep failed - bucket may retain old versions${OFF}"
import subprocess, sys, json
b = sys.argv[1]
for key in ("Versions", "DeleteMarkers"):
    out = subprocess.run(["aws","s3api","list-object-versions","--bucket",b,"--output","json"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("    bucket not reachable or already gone"); break
    items = (json.loads(out.stdout or "{}") or {}).get(key) or []
    for it in items:
        subprocess.run(["aws","s3api","delete-object","--bucket",b,
                        "--key",it["Key"],"--version-id",it["VersionId"]],
                       capture_output=True)
    if items:
        print(f"    removed {len(items)} {key}")
PY
  for q in "${CONFIG_CHANGE_QUEUE:-config-change-queue}" "${CONFIG_CHANGE_DLQ:-config-change-dlq}"; do
    url=$(aws sqs get-queue-url --queue-name "$q" --query QueueUrl --output text 2>/dev/null)
    if [ -n "$url" ] && [ "$url" != None ]; then
      aws sqs delete-queue --queue-url "$url" >/dev/null 2>&1 && echo "    deleted queue $q" \
        || echo "    ${YEL}could not delete queue $q${OFF}"
    else
      echo "    queue $q not present"
    fi
  done
  echo "    ${YEL}re-provision before the next deploy: ./scripts/provision-floci.sh${OFF}"
fi

if [ "$DO_DELETE" -eq 1 ]; then
  if [ "$TARGET" = eks ]; then
    echo "==> Deleting the EKS cluster"
    aws eks delete-cluster --name "$CLUSTER" >/dev/null 2>&1 \
      && echo "    ${GREEN}cluster deleted${OFF}" || echo "    ${YEL}delete-cluster failed or already gone${OFF}"
    # The kubeconfig points at an API server that no longer exists; leaving it invites confusing
    # "connection refused" errors on the next run.
    rm -f "$KC" && echo "    removed stale kubeconfig k8s/floci-eks.kubeconfig"
  else
    echo "==> Deleting the minikube VM"
    minikube delete && echo "    ${GREEN}cluster deleted${OFF}"
  fi
elif [ "$DO_STOP" -eq 1 ]; then
  if [ "$TARGET" = eks ]; then
    echo "==> Stopping Floci"
    floci stop >/dev/null 2>&1 && echo "    ${GREEN}stopped${OFF} (resume with: floci start)" \
      || echo "    ${YEL}floci stop failed${OFF}"
  else
    echo "==> Stopping the minikube VM"
    minikube stop && echo "    ${GREEN}stopped${OFF} (resume with: minikube start)"
  fi
fi

echo
echo "${BOLD}==> Remaining state${OFF}"
if [ "$TARGET" = eks ]; then
  if docker inspect "floci-eks-$CLUSTER" >/dev/null 2>&1; then
    echo "  cluster:   k3s node floci-eks-$CLUSTER running"
    echo "  namespace: $(k get namespace $NS >/dev/null 2>&1 && echo present || echo gone)"
    echo "  images:    $(docker exec "floci-eks-$CLUSTER" ctr -n k8s.io images ls -q 2>/dev/null | grep -c 'config-s3-demo-') config-s3-demo tag(s) in containerd"
  else
    echo "  cluster:   not running"
  fi
else
  if minikube status >/dev/null 2>&1; then
    echo "  cluster:   running"
    echo "  namespace: $(kubectl get namespace $NS >/dev/null 2>&1 && echo present || echo gone)"
    echo "  images:    $(minikube image ls 2>/dev/null | grep -c -E 'config-s3-demo|rabbitmq:4-management') of 4 still loaded"
  else
    echo "  cluster:   not running"
  fi
fi
echo "  S3/SQS:    $([ "$DO_PURGE_AWS" -eq 1 ] && echo 'purged - rerun ./scripts/provision-floci.sh' || echo 'untouched (configuration is safe)')"
