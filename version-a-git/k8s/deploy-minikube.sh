#!/usr/bin/env bash
# Deploys version A (Git) to a local minikube cluster and verifies it end to end.
#
# Images are built on the HOST Docker daemon and then loaded into minikube, rather than built
# inside minikube via `eval $(minikube docker-env)`. That is not a preference: on this machine the
# minikube VM cannot pull from Docker Hub -
#   tls: failed to verify certificate: x509: certificate signed by unknown authority
# because a corporate TLS certificate the host trusts is not present in the VM's trust store.
# Building on the host sidesteps the pull entirely.
set -euo pipefail

# ---------------------------------------------------------------------------------------------
# PREREQUISITE - this stack will NOT come up without it:
#   CONFIG_REPO_URI in k8s/00-namespace-and-config.yaml must point at a REACHABLE Git remote.
#   The file:// backend CANNOT run in a cluster: the Config Server treats the repository
#   directory as its working tree and performs a real `git checkout`, so replicas would need one
#   shared RWX volume and would corrupt each other.
#
#   The default URI is this project's own public GitHub repo, which clones anonymously - so
#   CONFIG_REPO_USERNAME/PASSWORD in the Secret are left as REPLACE_ME on purpose and are never
#   bound (username/password are commented out in config-server/application.yml). Point the URI
#   at a PRIVATE remote and you must fill both in, and uncomment those two lines.
#
#   The verification step below COMMITS AND PUSHES to that remote (and pushes a revert
#   afterwards), so you need push access to it.
# ---------------------------------------------------------------------------------------------

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
# -q by default keeps the deploy output readable, but it also means a slow build looks hung.
# Override for a noisy run, or add -o to skip remote dependency resolution entirely when the
# local repository is already warm:
#   MVN_FLAGS="-B -o -Pfast" ./k8s/deploy-minikube.sh
MVN_FLAGS="${MVN_FLAGS:--B -q -Pfast}"
(cd "$ROOT" && mvn $MVN_FLAGS clean install -DskipTests)

echo "==> Building images on the host daemon"
(cd "$ROOT" && docker compose -f docker/compose.yaml build config-server inventory-service pricing-service >/dev/null)

echo "==> Loading images into minikube"
for img in config-git-demo-config-server:latest \
           config-git-demo-inventory-service:latest \
           config-git-demo-pricing-service:latest \
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

Change configuration (this is the whole point - no restart, no redeploy). The Git backend's
source of truth is the REMOTE, so edit, commit and push, then broadcast one refresh:
  vi version-a-git/config-repo/inventory-service.yml    # e.g. max-order-quantity: 750
  git commit -am "raise max order quantity" && git push

  # /monitor rejects even correctly signed payloads, so trigger the bus directly:
  kubectl -n config-demo exec deploy/config-server -c config-server -- \
    wget -qO- --post-data='' \
    --header="Authorization: Basic $(printf 'config-admin:admin-secret' | base64)" \
    http://localhost:9888/actuator/busrefresh

Tear down (tiered - run with --help to see the options):
  ./k8s/teardown-minikube.sh                   # namespace only; next deploy is fast
  ./k8s/teardown-minikube.sh --stop            # also stop the VM
  ./k8s/teardown-minikube.sh --delete-cluster  # start completely clean next time
EOF
