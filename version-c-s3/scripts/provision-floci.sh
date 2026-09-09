#!/usr/bin/env bash
# Provisions the S3 bucket, SQS queues and S3 -> SQS notification wiring in Floci.
#
# Floci is the local AWS emulator already installed on this machine (an alternative to
# LocalStack), reachable on the same endpoint port. Because provisioning uses the real AWS APIs,
# the deployment delta to real AWS is only: drop AWS_ENDPOINT and the static credentials, and
# attach an IAM role.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost.floci.io:4566}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

BUCKET="${CONFIG_BUCKET:-acme-platform-config}"
QUEUE="${CONFIG_CHANGE_QUEUE:-config-change-queue}"
DLQ="${CONFIG_CHANGE_DLQ:-config-change-dlq}"
PREFIX="${CONFIG_KEY_PREFIX:-main/}"

if ! floci status >/dev/null 2>&1; then
  echo "Floci is not running. Start it with: floci start" >&2
  exit 1
fi

echo "Provisioning Floci at $AWS_ENDPOINT_URL"

# ------------------------------------------------------------------ bucket
if ! aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  aws s3 mb "s3://$BUCKET" >/dev/null
  echo "  created bucket $BUCKET"
else
  echo "  bucket $BUCKET already exists"
fi

# Versioning is what replaces `git log` for this backend: it provides the audit trail and makes
# rollback a CopyObject from a prior versionId.
aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled >/dev/null
echo "  versioning enabled (audit + rollback)"

# ------------------------------------------------------------------ queues
aws sqs create-queue --queue-name "$DLQ" >/dev/null 2>&1 || true
DLQ_URL=$(aws sqs get-queue-url --queue-name "$DLQ" --query QueueUrl --output text)
DLQ_ARN=$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

# maxReceiveCount bounds poison-message retries: a body that always throws lands in the DLQ
# instead of blocking the queue forever.
REDRIVE="{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"3\"}"
aws sqs create-queue --queue-name "$QUEUE" \
  --attributes "{\"VisibilityTimeout\":\"30\",\"RedrivePolicy\":$(python3 -c "import json,sys;print(json.dumps(sys.argv[1]))" "$REDRIVE")}" \
  >/dev/null 2>&1 || true
QUEUE_URL=$(aws sqs get-queue-url --queue-name "$QUEUE" --query QueueUrl --output text)
QUEUE_ARN=$(aws sqs get-queue-attributes --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
echo "  queue $QUEUE ($QUEUE_ARN) with DLQ $DLQ, maxReceiveCount=3"

# ------------------------------------------------------------------ notification
aws s3api put-bucket-notification-configuration --bucket "$BUCKET" \
  --notification-configuration "{
    \"QueueConfigurations\": [{
      \"QueueArn\": \"$QUEUE_ARN\",
      \"Events\": [\"s3:ObjectCreated:*\", \"s3:ObjectRemoved:*\"],
      \"Filter\": {\"Key\": {\"FilterRules\": [{\"Name\": \"prefix\", \"Value\": \"$PREFIX\"}]}}
    }]
  }" >/dev/null
echo "  S3 -> SQS notifications configured for prefix '$PREFIX'"

# ------------------------------------------------------------------ seed config
aws s3 cp "$HERE/../seed-config/" "s3://$BUCKET/$PREFIX" --recursive --exclude '*' \
  --include '*.yml' >/dev/null
echo "  seeded configuration objects:"
aws s3 ls "s3://$BUCKET/$PREFIX" | sed 's/^/    /'

echo "Provisioning complete."
