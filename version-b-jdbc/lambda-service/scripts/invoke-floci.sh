#!/usr/bin/env bash
# Calls lambda-service through its API Gateway route and prints the JSON it returns, e.g.
#   {"greeting":"Hello from AWS Lambda","featureEnabled":true,"maxItems":10}
#
#   # from: anywhere, after ./scripts/deploy-floci.sh
#   ./scripts/invoke-floci.sh
#
# Exits non-zero (and prints the problem details) if the call does not return HTTP 200.
set -euo pipefail

API_NAME=config-jdbc-lambda-service-api
export AWS_ENDPOINT_URL=${AWS_ENDPOINT_URL:-http://localhost:4566}
export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-test}
export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-test}
export AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-us-east-1}

API_ID=$(aws apigatewayv2 get-apis --query "Items[?Name=='$API_NAME'].ApiId | [0]" --output text)
if [ "$API_ID" = "None" ] || [ -z "$API_ID" ]; then
  echo "API $API_NAME not found - run ./scripts/deploy-floci.sh first" >&2
  exit 1
fi
curl -sS --fail-with-body "$AWS_ENDPOINT_URL/_aws/execute-api/$API_ID/\$default/api/v1/lambda/config"
echo
