#!/usr/bin/env bash
# Tears down the version C (S3 backend) Docker Compose stack.
#
# Tiered, like k8s/teardown.sh, because "teardown" means different things and the expensive one is
# slow to undo.
#
# WHAT IS NOT IN THIS STACK: S3 and SQS. Floci runs OUTSIDE Compose (`floci start`), which is why
# there is no emulator container to remove here - and why `docker compose down` cannot lose your
# configuration, since it lives in the bucket. Two consequences:
#
#   - Stopping Floci is a separate flag (--stop-floci), because other projects may be using it.
#   - Destroying the configuration is a separate flag (--purge-aws), and only
#     ./scripts/provision-floci.sh can put it back.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
COMPOSE="$ROOT/docker/compose.yaml"
PROJECT=config-s3-demo

RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

DO_STOP_ONLY=0
DO_VOLUMES=0
DO_IMAGES=0
DO_BASE_IMAGES=0
DO_JARS=0
DO_STOP_FLOCI=0
DO_PURGE_AWS=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./scripts/teardown-docker.sh [options]

With no options: `docker compose down --remove-orphans` - removes the 5 containers and the
project network. Images stay, so the next `up` is immediate. S3, SQS and Floci are untouched,
so your configuration is safe.

Options:
  --stop            Only STOP the containers, do not remove them. Fastest to resume
                    (`docker compose start`).
  --volumes         Also remove anonymous volumes (`down -v`). RabbitMQ's base image declares a
                    VOLUME, so each `up` leaves one behind.
  --images          Also remove the 5 images built from this project
                    (config-s3-demo-config-server / -inventory-service / -pricing-service /
                    -node-service / -go-service).
                    Includes any per-deploy 1.0.0-<timestamp> tags left by deploy-floci-eks.sh.
                    The next `up` must rebuild - run `mvn -Pfast package` first.
  --base-images     Also remove the pulled base image (rabbitmq:4-management).
                    CAUTION: k8s/deploy-minikube.sh loads it from the HOST daemon into minikube,
                    and the VM cannot pull it itself.
  --jars            Also run `mvn clean` to delete local target/ directories.
  --stop-floci      Also `floci stop`. Separate because other projects may be using Floci; the
                    bucket and queues survive a stop.
  --purge-aws       DESTRUCTIVE and separate on purpose: empties the S3 bucket (all object
                    versions) and deletes both SQS queues - the configuration source of truth.
                    Recoverable only by rerunning ./scripts/provision-floci.sh.
  --all             Same as --volumes --images --jars
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./scripts/teardown-docker.sh                     # remove containers and network
  ./scripts/teardown-docker.sh --stop              # pause, resume with `docker compose start`
  ./scripts/teardown-docker.sh --all --stop-floci  # reclaim everything and shut the emulator down
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --stop)         DO_STOP_ONLY=1 ;;
    --volumes)      DO_VOLUMES=1 ;;
    --images)       DO_IMAGES=1 ;;
    --base-images)  DO_BASE_IMAGES=1 ;;
    --jars)         DO_JARS=1 ;;
    --stop-floci)   DO_STOP_FLOCI=1 ;;
    --purge-aws)    DO_PURGE_AWS=1 ;;
    --all)          DO_VOLUMES=1; DO_IMAGES=1; DO_JARS=1 ;;
    -y|--yes)       ASSUME_YES=1 ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; echo; usage >&2; exit 2 ;;
  esac
  shift
done

command -v docker >/dev/null || { echo "docker not installed" >&2; exit 1; }
[ -f "$COMPOSE" ] || { echo "no compose file at $COMPOSE" >&2; exit 1; }

dc() { docker compose -f "$COMPOSE" "$@"; }

if [ "$DO_PURGE_AWS" -eq 1 ]; then
  export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
  export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
  export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
  export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
fi

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it. --purge-aws is the only flag here
# that can lose work, so it is always called out explicitly - in either direction.
# --------------------------------------------------------------------------------------------
running=$(dc ps --status running -q 2>/dev/null | grep -c . || true)
echo "${BOLD}This will:${OFF}"
if [ "$DO_STOP_ONLY" -eq 1 ]; then
  echo "  - stop $running running container(s), keeping them for a fast restart"
else
  echo "  - remove the containers and the '$PROJECT' network ($running running)"
fi
[ "$DO_VOLUMES"     -eq 1 ] && echo "  - remove anonymous volumes belonging to this project"
[ "$DO_IMAGES"      -eq 1 ] && echo "  - remove this project's built images, including per-deploy 1.0.0-* tags"
[ "$DO_BASE_IMAGES" -eq 1 ] && echo "  - ${YEL}remove rabbitmq:4-management - this breaks k8s/deploy-minikube.sh until you pull it again${OFF}"
[ "$DO_JARS"        -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
[ "$DO_STOP_FLOCI"  -eq 1 ] && echo "  - stop Floci ('floci start' resumes it; the bucket and queues survive)"
if [ "$DO_PURGE_AWS" -eq 1 ]; then
  echo "  - ${RED}EMPTY the S3 bucket and DELETE both SQS queues - this is the configuration itself${OFF}"
  echo "    (recover with: ./scripts/provision-floci.sh)"
else
  echo "  - leave S3 and SQS untouched: configuration lives outside Docker"
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

if [ "$DO_STOP_ONLY" -eq 1 ]; then
  echo "==> Stopping containers"
  dc stop && echo "    ${GREEN}stopped${OFF} (resume with: docker compose -f docker/compose.yaml start)"
else
  echo "==> Removing containers and network"
  if [ "$DO_VOLUMES" -eq 1 ]; then
    dc down --volumes --remove-orphans && echo "    ${GREEN}down, volumes removed${OFF}"
  else
    dc down --remove-orphans && echo "    ${GREEN}down${OFF}"
  fi
fi

if [ "$DO_IMAGES" -eq 1 ]; then
  echo "==> Removing images built from this project"
  for img in ${PROJECT}-config-server:latest \
             ${PROJECT}-inventory-service:latest \
             ${PROJECT}-pricing-service:latest \
             ${PROJECT}-node-service:latest \
             ${PROJECT}-go-service:latest; do
    printf '    %-45s' "$img"
    docker rmi "$img" >/dev/null 2>&1 && echo "removed" || echo "not present"
  done
  # deploy-floci-eks.sh tags every build 1.0.0-<timestamp> so the kubelet cannot pin a stale
  # :latest; those tags accumulate on the host daemon and nothing else cleans them up.
  extra=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E "^${PROJECT}-.*:1\.0\.0-" || true)
  if [ -n "$extra" ]; then
    echo "    per-deploy tags: removing $(echo "$extra" | wc -l | tr -d ' ')"
    echo "$extra" | xargs -r docker rmi >/dev/null 2>&1 || true
  fi
fi

if [ "$DO_BASE_IMAGES" -eq 1 ]; then
  echo "==> Removing pulled base images"
  printf '    %-45s' "rabbitmq:4-management"
  docker rmi rabbitmq:4-management >/dev/null 2>&1 && echo "removed" || echo "not present / still in use"
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
  # the bucket is not actually empty. Delete markers included.
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
  echo "    ${YEL}re-provision before the next 'up': ./scripts/provision-floci.sh${OFF}"
fi

if [ "$DO_STOP_FLOCI" -eq 1 ]; then
  echo "==> Stopping Floci"
  if command -v floci >/dev/null; then
    floci stop >/dev/null 2>&1 && echo "    ${GREEN}stopped${OFF} (resume with: floci start)" \
      || echo "    ${YEL}floci stop failed${OFF}"
  else
    echo "    ${YEL}floci not on PATH${OFF}"
  fi
fi

echo
echo "${BOLD}==> Remaining state${OFF}"
echo "  containers: $(dc ps -a -q 2>/dev/null | grep -c . || echo 0)"
echo "  images:     $(docker images --format '{{.Repository}}' | grep -c "^${PROJECT}-" || true) tag(s) matching ${PROJECT}-*"
if command -v floci >/dev/null && floci status >/dev/null 2>&1; then
  echo "  floci:      running"
  echo "  S3/SQS:     $([ "$DO_PURGE_AWS" -eq 1 ] && echo 'purged - rerun ./scripts/provision-floci.sh' || echo 'untouched (configuration is safe)')"
else
  echo "  floci:      not running (the bucket and queues persist across a stop)"
fi
