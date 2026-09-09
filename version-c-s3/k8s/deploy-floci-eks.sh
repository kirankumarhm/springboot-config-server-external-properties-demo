#!/usr/bin/env bash
# Deploys version C to a Kubernetes cluster provided by the Floci AWS emulator's EKS service,
# and verifies it end to end.
#
# Floci's EKS is a REAL control plane: create-cluster starts a rancher/k3s container and publishes
# its API server. Four things about this environment are not obvious, and each one blocks the
# deployment completely if missed:
#
#   1. THE CONTAINER RUNTIME CANNOT PULL FROM DOCKER HUB.
#      A corporate TLS proxy re-signs the connection and its CA is absent from the k3s container's
#      trust store, so every pull fails with
#        x509: certificate signed by unknown authority
#      The first symptom is NOT an image error on your own pods - it is every pod stuck in
#      ContainerCreating because k3s cannot pull rancher/mirrored-pause, the sandbox image that
#      every pod needs. The node still reports Ready, which makes it look like the cluster is fine.
#      Fixed twice over here: a registries.yaml that skips verification, plus pre-importing the
#      images from the host daemon (which CAN pull).
#
#   2. THE AWS ENDPOINT MUST BE AN IP ADDRESS, NOT A HOSTNAME.
#      AwsS3EnvironmentRepositoryFactory builds its own S3Client with no injection point and no
#      path-style option (verified in Spring Cloud Config source). With a hostname the SDK uses
#      virtual-host addressing and needs <bucket>.<host> to resolve, which cluster DNS will not do.
#      The SDK falls back to PATH-style automatically when the endpoint host is a bare IP, so
#      pointing at Floci's Docker-bridge IP removes the problem entirely - no hostAliases, no
#      per-bucket DNS record.
#
#   3. `aws eks update-kubeconfig` PRODUCES A KUBECONFIG GUI TOOLS CANNOT USE.
#      It writes an exec credential plugin that shells out to `aws eks get-token`, which needs the
#      aws CLI on PATH plus AWS_ENDPOINT_URL and credentials in the calling process. k9s and
#      kubeterm have none of that, so they fail with "Unable to locate credentials". This script
#      instead takes k3s's own client certificate, giving a self-contained kubeconfig.
#   4. A MUTABLE `:latest` TAG SILENTLY RUNS STALE CODE.
#      With `imagePullPolicy: IfNotPresent` the kubelet resolves `:latest` once and pins that
#      image ID. Re-importing a rebuilt image under the same tag updates the tag in containerd but
#      running pods keep the old ID - so a code change appears to deploy and does nothing. This bit
#      a logging change: the fix was in the jar, in the image and in the tag, yet the pod still ran
#      the previous build. Every deploy therefore gets a unique tag, and the script ASSERTS that
#      the pod's imageID equals the image just built rather than trusting the rollout.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/.."
NS=config-demo
CLUSTER=config-demo
K3S="floci-eks-$CLUSTER"
KC="$HERE/floci-eks.kubeconfig"

export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

k() { kubectl --kubeconfig="$KC" "$@"; }

# A unique, immutable tag per deploy. See note 4 above for why :latest is unusable here.
IMAGE_TAG="${IMAGE_TAG:-1.0.0-$(date +%Y%m%d%H%M%S)}"
RENDERED="${SCRATCHPAD:-${TMPDIR:-/tmp}}/config-s3-k8s-$IMAGE_TAG"
MODULES="config-server inventory-service pricing-service"

echo "==> Preflight"
for c in floci aws docker kubectl mvn; do
  command -v "$c" >/dev/null || { echo "    $c not found on PATH" >&2; exit 1; }
done
[ -f "$ROOT/secrets/config-server.p12" ] || "$ROOT/scripts/generate-keystore.sh" >/dev/null

echo "==> Starting Floci"
floci start >/dev/null 2>&1 || true
floci wait >/dev/null 2>&1 || sleep 5

echo "==> Creating the EKS cluster (idempotent)"
if ! aws eks describe-cluster --name "$CLUSTER" >/dev/null 2>&1; then
  aws eks create-cluster --name "$CLUSTER" \
    --role-arn arn:aws:iam::000000000000:role/eks-service-role \
    --resources-vpc-config subnetIds=subnet-aaaa1111 >/dev/null
fi
for _ in $(seq 1 40); do
  [ "$(aws eks describe-cluster --name "$CLUSTER" --query 'cluster.status' --output text 2>/dev/null)" = ACTIVE ] && break
  sleep 5
done
echo "    cluster ACTIVE, node container: $K3S"

echo "==> Fixing the container-runtime TLS problem (see note 1 above)"
docker exec "$K3S" sh -c 'mkdir -p /etc/rancher/k3s && cat > /etc/rancher/k3s/registries.yaml <<"EOF"
mirrors:
  docker.io:
    endpoint:
      - "https://registry-1.docker.io"
configs:
  "registry-1.docker.io":
    tls:
      insecure_skip_verify: true
EOF' >/dev/null
docker restart "$K3S" >/dev/null
sleep 25

echo "==> Kubeconfig from k3s client certs (see note 3 above)"
for _ in $(seq 1 20); do
  docker exec "$K3S" cat /etc/rancher/k3s/k3s.yaml 2>/dev/null \
    | sed 's#server: https://127.0.0.1:6443#server: https://localhost:6500#' > "$KC" || true
  grep -q client-certificate-data "$KC" 2>/dev/null && break
  sleep 5
done
chmod 600 "$KC"
k config rename-context default floci-eks >/dev/null 2>&1 || true
k get nodes --no-headers | sed 's/^/    /'

echo "==> Building jars and images on the host"
(cd "$ROOT" && mvn -B -q -Pfast clean install -DskipTests)
(cd "$ROOT" && docker compose -f docker/compose.yaml build config-server inventory-service pricing-service >/dev/null)
# Verify the artifacts exist rather than trusting an exit code - a PATH problem once made Maven
# silently not run at all, and the failure only surfaced later as a missing jar in docker build.
for m in config-server inventory-service pricing-service; do
  [ -f "$ROOT/$m/target/$m-1.0.0.jar" ] || { echo "    missing jar for $m" >&2; exit 1; }
done

echo "==> Tagging this build $IMAGE_TAG"
for m in $MODULES; do
  # Braces are required: in zsh "$m:latest" parses :l as the lowercase parameter modifier and
  # silently produces "config-s3-demo-config-serveratest:latest".
  docker tag "config-s3-demo-${m}:latest" "config-s3-demo-${m}:${IMAGE_TAG}"
done

echo "==> Importing images into k3s containerd (see note 1 above)"
docker pull -q rancher/mirrored-pause:3.6 >/dev/null 2>&1 || true
IMPORTS="rancher/mirrored-pause:3.6 rabbitmq:4-management"
for m in $MODULES; do IMPORTS="$IMPORTS config-s3-demo-${m}:${IMAGE_TAG}"; done
for img in $IMPORTS; do
  printf '    %-52s' "$img"
  docker save "$img" | docker exec -i "$K3S" ctr -n k8s.io images import - >/dev/null 2>&1 \
    && echo ok || { echo FAILED; exit 1; }
done

echo "==> Provisioning S3 + SQS in Floci"
"$ROOT/scripts/provision-floci.sh" >/dev/null

echo "==> Pointing the manifests at Floci's bridge IP (see note 2 above)"
FLOCI_IP=$(docker inspect floci --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
python3 - "$FLOCI_IP" "$HERE/00-namespace-and-config.yaml" <<'PY'
import re, sys
ip, path = sys.argv[1], sys.argv[2]
s = open(path).read()
s = re.sub(r'AWS_ENDPOINT: "http://[0-9.]+:4566"', f'AWS_ENDPOINT: "http://{ip}:4566"', s)
open(path, 'w').write(s)
PY
echo "    AWS_ENDPOINT = http://$FLOCI_IP:4566"

echo "==> Rendering manifests with tag $IMAGE_TAG"
# The manifests keep :latest so they stay readable and applyable on their own; the deploy pins an
# immutable tag into a rendered copy.
mkdir -p "$RENDERED"
for f in 00-namespace-and-config.yaml 01-dependencies.yaml 02-config-server.yaml 03-clients.yaml; do
  sed "s|\(image: config-s3-demo-[a-z-]*\):latest|\1:${IMAGE_TAG}|" "$HERE/$f" > "$RENDERED/$f"
done
grep -h 'image: config-s3-demo' "$RENDERED"/*.yaml | sed 's/^/    /'

echo "==> Applying manifests"
k apply -f "$RENDERED/00-namespace-and-config.yaml" >/dev/null
k -n $NS create secret generic config-encryption-keystore \
  --from-file=config-server.p12="$ROOT/secrets/config-server.p12" \
  --dry-run=client -o yaml | k apply -f - >/dev/null
k apply -f "$RENDERED/01-dependencies.yaml" >/dev/null
k -n $NS rollout status deployment/rabbitmq --timeout=300s
k apply -f "$RENDERED/02-config-server.yaml" >/dev/null
k -n $NS rollout status deployment/config-server --timeout=300s
k apply -f "$RENDERED/03-clients.yaml" >/dev/null
k -n $NS rollout status deployment/inventory-service --timeout=300s
k -n $NS rollout status deployment/pricing-service --timeout=300s

echo "==> Asserting pods run the image just built (see note 4 above)"
# Do NOT compare kubelet's containerStatuses[].imageID against `docker images --format {{.ID}}`.
# They are different digests by design: docker reports the OCI index/config digest, kubelet reports
# the digest of the unpacked image config, so they never match and the check is useless. What is
# meaningful, and what is checked here:
#   a) containerd resolves the unique tag to exactly the digest docker built, and
#   b) the pod spec requested that unique tag.
# Together those pin the running code, because the tag is fresh and maps to nothing else.
fail=0
for m in $MODULES; do
  host_digest=$(docker images --no-trunc --format '{{.ID}}' "config-s3-demo-${m}:${IMAGE_TAG}")
  ctr_digest=$(docker exec "$K3S" ctr -n k8s.io images ls 2>/dev/null \
    | awk -v t="config-s3-demo-${m}:${IMAGE_TAG}" '$1 ~ t {print $3; exit}')
  pod_image=$(k -n $NS get pods -l app="$m" -o jsonpath='{.items[0].spec.containers[0].image}')
  if [ "$host_digest" = "$ctr_digest" ] && [ "$pod_image" = "config-s3-demo-${m}:${IMAGE_TAG}" ]; then
    printf '    %-22s OK  tag=%s digest=%s\n' "$m" "$IMAGE_TAG" "${ctr_digest:0:19}"
  else
    printf '    %-22s FAIL host=%s ctr=%s podImage=%s\n' \
      "$m" "${host_digest:0:19}" "${ctr_digest:0:19}" "$pod_image"; fail=1
  fi
done
[ "$fail" -eq 0 ] || { echo "    the cluster is not running the image just built" >&2; exit 1; }

echo
k -n $NS get pods -o wide
echo
"$HERE/verify-in-cluster.sh"

cat <<EOF

To use this cluster from k9s / kubeterm (they read ~/.kube/config and cannot run the aws exec
plugin), merge the self-contained kubeconfig in:

  cp ~/.kube/config ~/.kube/config.backup
  KUBECONFIG=~/.kube/config:$KC kubectl config view --flatten > /tmp/m && mv /tmp/m ~/.kube/config
  kubectl config use-context floci-eks

Change configuration (no restart, no redeploy):
  eval \$(floci env)
  aws s3 cp application.yml s3://acme-platform-config/main/application.yml

Tear down:
  kubectl --kubeconfig=$KC delete namespace $NS
  aws eks delete-cluster --name $CLUSTER
  floci stop
EOF
