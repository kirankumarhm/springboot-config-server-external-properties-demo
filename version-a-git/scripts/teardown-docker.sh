#!/usr/bin/env bash
# Tears down the version A (Git backend) Docker Compose stack.
#
# Tiered, like k8s/teardown-minikube.sh, because "teardown" means different things: stopping the
# stack for lunch and reclaiming every byte it ever used are not the same request, and the
# expensive one is slow to undo. By default this removes the containers and the network, which is
# what `docker compose down` does and what you almost always want.
#
# Nothing here touches config-repo/. That directory IS the configuration - the file:// backend
# serves it and it is its own Git repository - so tearing the stack down cannot lose it. The one
# thing this script does check is whether a leftover test value is still sitting in it, because
# that value would be served as if it were real on the next `up`.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
COMPOSE="$ROOT/docker/compose.yaml"
PROJECT=config-git-demo

RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

DO_STOP_ONLY=0
DO_VOLUMES=0
DO_IMAGES=0
DO_BASE_IMAGES=0
DO_JARS=0
DO_HOOK=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./scripts/teardown-docker.sh [options]

With no options: `docker compose down --remove-orphans` - removes the 5 containers and the
project network. Images stay, so the next `up` is immediate. config-repo/ is never touched.

Options:
  --stop            Only STOP the containers, do not remove them. Fastest to resume
                    (`docker compose start`). Use this when you are coming back to it.
  --volumes         Also remove anonymous volumes (`down -v`). RabbitMQ's base image declares a
                    VOLUME, so each `up` leaves one behind; this is what reclaims them.
  --images          Also remove the 5 images built from this project
                    (config-git-demo-config-server / -inventory-service / -pricing-service /
                    -node-service / -go-service).
                    The next `up` must rebuild them - run `mvn -Pfast package` first.
  --base-images     Also remove the pulled base image (rabbitmq:4-management).
                    CAUTION: k8s/deploy-minikube.sh loads that image from the HOST daemon into
                    minikube, and the minikube VM cannot pull it itself - so removing it here
                    breaks the Kubernetes deploy until you `docker pull rabbitmq:4-management`.
  --hook            Also uninstall the post-commit hook from config-repo/.git/hooks.
  --jars            Also run `mvn clean` to delete local target/ directories.
  --all             Same as --volumes --images --jars
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./scripts/teardown-docker.sh                # remove containers and network; next up is instant
  ./scripts/teardown-docker.sh --stop         # pause for now, resume with `docker compose start`
  ./scripts/teardown-docker.sh --all -y       # reclaim everything this stack built, no prompt
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --stop)         DO_STOP_ONLY=1 ;;
    --volumes)      DO_VOLUMES=1 ;;
    --images)       DO_IMAGES=1 ;;
    --base-images)  DO_BASE_IMAGES=1 ;;
    --hook)         DO_HOOK=1 ;;
    --jars)         DO_JARS=1 ;;
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

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it.
# --------------------------------------------------------------------------------------------
running=$(dc ps --status running -q 2>/dev/null | grep -c . || true)
echo "${BOLD}This will:${OFF}"
if [ "$DO_STOP_ONLY" -eq 1 ]; then
  echo "  - stop $running running container(s), keeping them for a fast restart"
else
  echo "  - remove the containers and the '$PROJECT' network ($running running)"
fi
[ "$DO_VOLUMES"     -eq 1 ] && echo "  - remove anonymous volumes belonging to this project"
[ "$DO_IMAGES"      -eq 1 ] && echo "  - remove the 5 images built from this project (next up must rebuild)"
[ "$DO_BASE_IMAGES" -eq 1 ] && echo "  - ${YEL}remove rabbitmq:4-management - this breaks k8s/deploy-minikube.sh until you pull it again${OFF}"
[ "$DO_HOOK"        -eq 1 ] && echo "  - uninstall config-repo/.git/hooks/post-commit"
[ "$DO_JARS"        -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
echo "  - leave config-repo/ untouched: it is the configuration source of truth"
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
fi

if [ "$DO_BASE_IMAGES" -eq 1 ]; then
  echo "==> Removing pulled base images"
  printf '    %-45s' "rabbitmq:4-management"
  docker rmi rabbitmq:4-management >/dev/null 2>&1 && echo "removed" || echo "not present / still in use"
fi

if [ "$DO_HOOK" -eq 1 ]; then
  echo "==> Uninstalling the post-commit hook"
  HOOK="$ROOT/config-repo/.git/hooks/post-commit"
  if [ -f "$HOOK" ]; then
    rm -f "$HOOK" && echo "    ${GREEN}removed${OFF} (reinstall with ./scripts/install-git-hook.sh)"
  else
    echo "    not installed"
  fi
fi

if [ "$DO_JARS" -eq 1 ]; then
  echo "==> Deleting local build output"
  # -o (offline) because `clean` needs no network; without it this can stall on dependency checks.
  (cd "$ROOT" && mvn -B -q -o clean) && echo "    ${GREEN}target/ removed${OFF}" \
    || echo "    ${YEL}mvn clean failed - remove */target manually${OFF}"
fi

echo
echo "${BOLD}==> Remaining state${OFF}"
echo "  containers: $(dc ps -a -q 2>/dev/null | grep -c . || echo 0)"
echo "  images:     $(docker images --format '{{.Repository}}' | grep -c "^${PROJECT}-" || true) of 5 built images present"
echo "  hook:       $([ -f "$ROOT/config-repo/.git/hooks/post-commit" ] && echo installed || echo 'not installed')"

# A leftover test value in config-repo is invisible once the stack is down, and the next `up`
# would serve it as if it were the real default.
LBL=$(grep -oE 'environment-label:[[:space:]]*"?[^"]*' "$ROOT/config-repo/application.yml" 2>/dev/null | sed 's/.*: *"*//')
case "$LBL" in
  e2e-*|k8s-verified-*|test-*) echo "  ${YEL}WARNING${OFF} config-repo still holds a test value: environment-label=$LBL"
                               echo "          revert it, or the next 'up' will serve it as real" ;;
  *)                           echo "  config:     environment-label=$LBL" ;;
esac
if ! git -C "$ROOT/config-repo" diff --quiet 2>/dev/null; then
  echo "  ${YEL}note${OFF}    config-repo has uncommitted changes - nothing has fetched them yet"
fi
