#!/usr/bin/env bash
# Deploys version B to a local minikube cluster and verifies it end to end.
#
# Images are built on the HOST Docker daemon and then loaded into minikube, rather than built
# inside minikube via `eval $(minikube docker-env)`. That is not a preference: on this machine the
# minikube VM cannot pull from Docker Hub -
#   tls: failed to verify certificate: x509: certificate signed by unknown authority
# because a corporate TLS certificate the host trusts is not present in the VM's trust store.
# Building on the host sidesteps the pull entirely.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
NS=config-demo

echo "==> Preflight"
command -v minikube >/dev/null || { echo "minikube not installed" >&2; exit 1; }
minikube status >/dev/null 2>&1 || {
  echo "    starting minikube"
  minikube start --driver=docker --cpus=4 --memory=6g --disk-size=20g
}
[ -f "$ROOT/secrets/config-server.p12" ] || {
  echo "    generating encryption keystore"
  "$ROOT/scripts/generate-keystore.sh"
}

echo "==> Building jars"
(cd "$ROOT" && mvn -B -q -Pfast clean install -DskipTests)

echo "==> Building images on the host daemon"
(cd "$ROOT" && docker compose -f docker/compose.yaml build config-server inventory-service pricing-service >/dev/null)

echo "==> Loading images into minikube"
for img in config-jdbc-demo-config-server:latest \
           config-jdbc-demo-inventory-service:latest \
           config-jdbc-demo-pricing-service:latest \
           postgres:17.6 \
           rabbitmq:4-management; do
  printf '    %-45s' "$img"
  minikube image load "$img" >/dev/null 2>&1 && echo "ok" || echo "FAILED"
done

echo "==> Applying manifests"
kubectl apply -f "$HERE/00-namespace-and-config.yaml"

# The private key is a genuine secret and is never committed, so the Secret is created from the
# local file rather than declared in a manifest. In a real cluster this comes from External
# Secrets / Sealed Secrets / Vault instead.
kubectl -n $NS create secret generic config-encryption-keystore \
  --from-file=config-server.p12="$ROOT/secrets/config-server.p12" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "$HERE/01-dependencies.yaml"
echo "==> Waiting for dependencies"
kubectl -n $NS rollout status statefulset/postgres --timeout=300s
kubectl -n $NS rollout status deployment/rabbitmq --timeout=300s

kubectl apply -f "$HERE/02-config-server.yaml"
echo "==> Waiting for the Config Server"
kubectl -n $NS rollout status deployment/config-server --timeout=300s

kubectl apply -f "$HERE/03-clients.yaml"
echo "==> Waiting for clients"
kubectl -n $NS rollout status deployment/inventory-service --timeout=300s
kubectl -n $NS rollout status deployment/pricing-service --timeout=300s

echo
kubectl -n $NS get pods -o wide
echo
echo "==> Verifying in-cluster"
"$HERE/verify-in-cluster.sh"

cat <<'EOF'

Reach the services from the host with:
  kubectl -n config-demo port-forward svc/inventory-service 8081:8081
  kubectl -n config-demo port-forward svc/config-server 9888:9888

Change configuration (this is the whole point - no restart, no redeploy):
  kubectl -n config-demo exec statefulset/postgres -- \
    psql -U config_admin -d configdb -c \
    "UPDATE properties SET \"value\"='750' WHERE application='inventory-service' AND \"key\"='inventory.max-order-quantity';"

Tear down:
  kubectl delete namespace config-demo
EOF
