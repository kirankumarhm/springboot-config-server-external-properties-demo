#!/usr/bin/env bash
# Tears down version B (PostgreSQL/JDBC) from a local minikube cluster.
#
# Deliberately tiered, because "teardown" means different things and the expensive one is hard to
# undo. By default this removes ONLY the config-demo namespace: the next deploy then skips the
# Maven build, the image build and the image load, so a redeploy is fast. Reach for --images or
# --delete-cluster only when you actually want to pay to rebuild.
#
# THE DIFFERENCE FROM VERSIONS A AND C, and the reason this script does more than delete a
# namespace: in version B the DATABASE IS THE SOURCE OF TRUTH. Version A's configuration lives in
# Git and version C's lives in S3, both outside the cluster, so tearing the cluster down cannot
# lose configuration. Here it can: the PersistentVolumeClaim (data-postgres-0) lives inside the
# namespace, so deleting the namespace deletes the configuration database with it. Every
# hand-edited property, and the whole properties_history audit trail, goes with it.
#
# So the namespace delete is preceded by an automatic pg_dump to k8s/backups/ (skip with
# --no-dump), and --keep-data exists to tear down the applications while leaving the database
# standing.
set -uo pipefail

NS=config-demo
RED=$'\033[31m'; GREEN=$'\033[32m'; YEL=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'

DO_IMAGES=0
DO_STOP=0
DO_DELETE=0
DO_JARS=0
KEEP_DATA=0
NO_DUMP=0
ASSUME_YES=0

usage() {
  cat <<'EOF'
Usage: ./k8s/teardown-minikube.sh [options]

With no options: dumps the configuration database to k8s/backups/, then deletes the config-demo
namespace (pods, services, configmaps, secrets AND the postgres PersistentVolumeClaim). The
cluster keeps running and the loaded images stay, so the next deploy is fast.

Options:
  --keep-data       Delete the APPLICATIONS only (config-server, clients, rabbitmq) and leave the
                    namespace, the postgres StatefulSet and its PVC intact. The database - and so
                    the configuration - survives, and the next deploy reuses it.
  --no-dump         Skip the automatic pg_dump. Only sensible with --keep-data, or when you are
                    certain the database holds nothing you want.
  --images          Also remove the five loaded images from the minikube VM.
                    The next deploy must rebuild and re-load them (slow).
  --jars            Also run `mvn clean` to delete local target/ directories.
  --stop            Also stop the minikube VM. Keeps the VM on disk; `minikube start` resumes it.
  --delete-cluster  Also DELETE the minikube VM entirely. Everything must be rebuilt from scratch.
  --all             Same as --images --jars --stop
  -y, --yes         Do not ask for confirmation.
  -h, --help        Show this message.

Examples:
  ./k8s/teardown-minikube.sh                   # dump, then free the namespace; redeploy quickly later
  ./k8s/teardown-minikube.sh --keep-data       # free the app pods, keep the configuration database
  ./k8s/teardown-minikube.sh --stop            # done for the day, reclaim laptop RAM
  ./k8s/teardown-minikube.sh --delete-cluster  # start completely clean next time
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-data)      KEEP_DATA=1 ;;
    --no-dump)        NO_DUMP=1 ;;
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
BACKUP_DIR="$HERE/backups"

command -v kubectl >/dev/null || { echo "kubectl not installed" >&2; exit 1; }

# --------------------------------------------------------------------------------------------
# Say exactly what is about to happen BEFORE doing any of it. Deleting the namespace here also
# deletes a database, which is not obvious from the word "teardown".
# --------------------------------------------------------------------------------------------
echo "${BOLD}This will:${OFF}"
NS_EXISTS=0
if kubectl get namespace "$NS" >/dev/null 2>&1; then
  NS_EXISTS=1
  pods=$(kubectl -n $NS get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
  if [ "$KEEP_DATA" -eq 1 ]; then
    echo "  - delete the config-server, inventory-service, pricing-service and rabbitmq workloads ($pods pod(s) running)"
    echo "  - ${GREEN}KEEP${OFF} the namespace, the postgres StatefulSet and its PVC - the database survives"
  else
    echo "  - delete namespace '$NS' ($pods pod(s) running)"
    echo "  - ${RED}DESTROY the configuration database${OFF} with it (PVC data-postgres-0), including properties_history"
  fi
else
  echo "  - ${YEL}namespace '$NS' does not exist - nothing to delete there${OFF}"
fi
if [ "$NO_DUMP" -eq 0 ] && [ "$NS_EXISTS" -eq 1 ]; then
  echo "  - first pg_dump the configuration database to k8s/backups/"
fi
[ "$DO_IMAGES" -eq 1 ] && echo "  - remove the 5 loaded images from the minikube VM (next deploy rebuilds them)"
[ "$DO_JARS"   -eq 1 ] && echo "  - run 'mvn clean' (deletes target/ in all three modules)"
[ "$DO_STOP"   -eq 1 ] && echo "  - stop the minikube VM (resumable with 'minikube start')"
[ "$DO_DELETE" -eq 1 ] && echo "  - ${RED}DELETE the minikube VM entirely - everything must be rebuilt${OFF}"
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
# Dump BEFORE anything is deleted. A schema-and-data dump of the whole database is small (the
# properties tables hold tens of rows), and it is the only way back if the PVC goes.
# --------------------------------------------------------------------------------------------
if [ "$NO_DUMP" -eq 0 ] && [ "$NS_EXISTS" -eq 1 ]; then
  echo "==> Dumping the configuration database"
  if kubectl -n $NS get statefulset/postgres >/dev/null 2>&1; then
    mkdir -p "$BACKUP_DIR"
    DUMP="$BACKUP_DIR/configdb-$(date +%Y%m%d%H%M%S).sql"
    if kubectl -n $NS exec statefulset/postgres -- \
         pg_dump -U config_admin -d configdb > "$DUMP" 2>/dev/null && [ -s "$DUMP" ]; then
      echo "    ${GREEN}$(wc -l < "$DUMP" | tr -d ' ') lines${OFF} -> ${DUMP#"$ROOT/"}"
      echo "    restore into a fresh deploy with:"
      echo "      kubectl -n $NS exec -i statefulset/postgres -- psql -U config_admin -d configdb < ${DUMP#"$ROOT/"}"
    else
      rm -f "$DUMP"
      echo "    ${YEL}dump failed or was empty - postgres may not be ready${OFF}"
      if [ "$KEEP_DATA" -eq 0 ] && [ "$ASSUME_YES" -eq 0 ]; then
        printf "    Continue and delete the database anyway? [y/N] "
        read -r reply
        case "$reply" in y|Y|yes|YES) ;; *) echo "aborted"; exit 0 ;; esac
      fi
    fi
  else
    echo "    ${YEL}no postgres StatefulSet - nothing to dump${OFF}"
  fi
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

if [ "$KEEP_DATA" -eq 1 ]; then
  echo "==> Deleting application workloads, keeping postgres"
  if [ "$NS_EXISTS" -eq 1 ]; then
    # Deleting the Deployments leaves the Services, ConfigMaps and Secrets in place, which is
    # exactly what a fast redeploy wants: `kubectl apply` puts the pods back and they find the
    # same database, same credentials and same keystore.
    kubectl -n $NS delete deployment config-server inventory-service pricing-service rabbitmq \
      --ignore-not-found --timeout=120s
    echo "    ${GREEN}done${OFF} - postgres and PVC data-postgres-0 retained"
  else
    echo "    namespace already gone"
  fi
else
  echo "==> Deleting namespace '$NS'"
  if [ "$NS_EXISTS" -eq 1 ]; then
    # A namespace delete blocks until every object inside is gone. --timeout keeps a stuck
    # finalizer from hanging this script forever; the warning below tells you how to look into it.
    if kubectl delete namespace "$NS" --timeout=180s; then
      echo "    ${GREEN}deleted${OFF}"
    else
      echo "    ${YEL}delete did not finish within 180s${OFF}"
      echo "    inspect with: kubectl get namespace $NS -o yaml   (look at spec.finalizers)"
    fi
  else
    echo "    already gone"
  fi
fi

if [ "$DO_IMAGES" -eq 1 ]; then
  echo "==> Removing loaded images from the minikube VM"
  for img in config-jdbc-demo-config-server:latest \
             config-jdbc-demo-inventory-service:latest \
             config-jdbc-demo-pricing-service:latest \
             postgres:17.6 \
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
  echo "  database:  $(kubectl -n $NS get pvc data-postgres-0 >/dev/null 2>&1 && echo "PVC data-postgres-0 retained" || echo gone)"
  echo "  images:    $(minikube image ls 2>/dev/null | grep -c -E 'config-jdbc-demo|postgres:17.6|rabbitmq:4-management') of 5 still loaded"
else
  echo "  cluster:   not running"
fi
if [ -d "$BACKUP_DIR" ]; then
  echo "  backups:   $(ls -1 "$BACKUP_DIR"/configdb-*.sql 2>/dev/null | wc -l | tr -d ' ') dump(s) in k8s/backups/"
fi
