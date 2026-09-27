#!/usr/bin/env bash
# Tears down the version B (PostgreSQL/JDBC backend) Docker Compose stack.
#
# Tiered, like k8s/teardown-minikube.sh, because "teardown" means different things and the
# expensive one is slow to undo.
#
# THE ONE THING THAT MAKES THIS VERSION DIFFERENT, and the reason this is not a copy of version
# A's script: THE DATABASE IS THE SOURCE OF TRUTH, AND IT HAS NO VOLUME. Look at the postgres
# service in docker/compose.yaml - there is no `volumes:` entry, so the data directory lives in
# the container's writable layer. That means:
#
#   docker compose stop   -> the database SURVIVES (the container still exists)
#   docker compose down   -> the database is DESTROYED, along with properties_history
#
# Version A's configuration lives in Git and version C's in S3, both outside Docker entirely, so
# no teardown can lose them. Here a plain `down` silently discards every hand-edited property. So
# the default path dumps the database to scripts/backups/ first, and --stop is offered as the
# non-destructive way to put the stack away.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
COMPOSE="$ROOT/docker/compose.yaml"
PROJECT=config-jdbc-demo
PG=cfg-jdbc-postgres
BACKUP_DIR="$HERE/backups"

RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

DO_STOP_ONLY=0
DO_VOLUMES=0
DO_IMAGES=0
DO_BASE_IMAGES=0
DO_JARS=0
NO_DUMP=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./scripts/teardown-docker.sh [options]

With no options: dumps the configuration database to scripts/backups/, then
`docker compose down --remove-orphans` - removes the 6 containers and the project network.
Images stay, so the next `up` is immediate.

  WARNING: postgres has no volume in this stack, so `down` DESTROYS the configuration
  database. Use --stop to put the stack away without losing it.

Options:
  --stop            Only STOP the containers, do not remove them. The database SURVIVES and
                    `docker compose start` brings everything back with your data intact.
  --no-dump         Skip the automatic pg_dump. Only sensible with --stop, or when you are
                    certain the database holds nothing you want.
  --volumes         Also remove anonymous volumes (`down -v`). The postgres and RabbitMQ base
                    images declare VOLUMEs, so each `up` leaves some behind.
  --images          Also remove the 3 images built from this project
                    (config-jdbc-demo-config-server / -inventory-service / -pricing-service).
                    The next `up` must rebuild them - run `mvn -Pfast package` first.
  --base-images     Also remove the pulled base images (postgres:17.6, rabbitmq:4-management).
                    CAUTION: k8s/deploy-minikube.sh loads both from the HOST daemon into
                    minikube, and the VM cannot pull them itself - so removing them here breaks
                    the Kubernetes deploy until you pull them again.
  --jars            Also run `mvn clean` to delete local target/ directories.
  --all             Same as --volumes --images --jars
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./scripts/teardown-docker.sh                # dump, then remove containers and network
  ./scripts/teardown-docker.sh --stop         # put it away, keep the database
  ./scripts/teardown-docker.sh --all -y       # reclaim everything this stack built, no prompt
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --stop)         DO_STOP_ONLY=1 ;;
    --no-dump)      NO_DUMP=1 ;;
    --volumes)      DO_VOLUMES=1 ;;
    --images)       DO_IMAGES=1 ;;
    --base-images)  DO_BASE_IMAGES=1 ;;
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
pg_running() { [ "$(docker inspect -f '{{.State.Running}}' "$PG" 2>/dev/null)" = true ]; }

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it. "down destroys the database" is
# not obvious from the word "teardown", so it is stated in red every time.
# --------------------------------------------------------------------------------------------
running=$(dc ps --status running -q 2>/dev/null | grep -c . || true)
echo "${BOLD}This will:${OFF}"
if [ "$DO_STOP_ONLY" -eq 1 ]; then
  echo "  - stop $running running container(s), keeping them"
  echo "  - ${GREEN}KEEP${OFF} the configuration database (the container is not removed)"
else
  echo "  - remove the containers and the '$PROJECT' network ($running running)"
  echo "  - ${RED}DESTROY the configuration database${OFF} - postgres has no volume in this stack"
fi
if [ "$NO_DUMP" -eq 0 ] && pg_running; then
  echo "  - first pg_dump the configuration database to scripts/backups/"
fi
[ "$DO_VOLUMES"     -eq 1 ] && echo "  - remove anonymous volumes belonging to this project"
[ "$DO_IMAGES"      -eq 1 ] && echo "  - remove the 3 images built from this project (next up must rebuild)"
[ "$DO_BASE_IMAGES" -eq 1 ] && echo "  - ${YEL}remove postgres:17.6 and rabbitmq:4-management - this breaks k8s/deploy-minikube.sh until you pull them again${OFF}"
[ "$DO_JARS"        -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
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
# Dump BEFORE anything is removed. The properties tables hold tens of rows, so this is small and
# fast, and it is the only way back once the container layer is gone.
# --------------------------------------------------------------------------------------------
if [ "$NO_DUMP" -eq 0 ]; then
  echo "==> Dumping the configuration database"
  if pg_running; then
    mkdir -p "$BACKUP_DIR"
    DUMP="$BACKUP_DIR/configdb-$(date +%Y%m%d%H%M%S).sql"
    if docker exec "$PG" pg_dump -U config_admin -d configdb > "$DUMP" 2>/dev/null && [ -s "$DUMP" ]; then
      echo "    ${GREEN}$(wc -l < "$DUMP" | tr -d ' ') lines${OFF} -> ${DUMP#"$ROOT/"}"
      echo "    restore into a fresh stack with:"
      echo "      docker exec -i $PG psql -U config_admin -d configdb < ${DUMP#"$ROOT/"}"
    else
      rm -f "$DUMP"
      echo "    ${YEL}dump failed or was empty${OFF}"
      if [ "$DO_STOP_ONLY" -eq 0 ] && [ "$ASSUME_YES" -eq 0 ]; then
        printf "    Continue and destroy the database anyway? [y/N] "
        read -r reply
        case "$reply" in y|Y|yes|YES) ;; *) echo "aborted"; exit 0 ;; esac
      fi
    fi
  else
    echo "    ${YEL}$PG is not running - nothing to dump${OFF}"
  fi
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
             ${PROJECT}-pricing-service:latest; do
    printf '    %-45s' "$img"
    docker rmi "$img" >/dev/null 2>&1 && echo "removed" || echo "not present"
  done
fi

if [ "$DO_BASE_IMAGES" -eq 1 ]; then
  echo "==> Removing pulled base images"
  for img in postgres:17.6 rabbitmq:4-management; do
    printf '    %-45s' "$img"
    docker rmi "$img" >/dev/null 2>&1 && echo "removed" || echo "not present / still in use"
  done
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
echo "  database:   $(docker inspect "$PG" >/dev/null 2>&1 && echo 'container present - data intact' || echo 'gone (restored only from a dump)')"
echo "  images:     $(docker images --format '{{.Repository}}' | grep -c "^${PROJECT}-" || true) of 3 built images present"
if [ -d "$BACKUP_DIR" ]; then
  echo "  backups:    $(ls -1 "$BACKUP_DIR"/configdb-*.sql 2>/dev/null | wc -l | tr -d ' ') dump(s) in scripts/backups/"
fi
