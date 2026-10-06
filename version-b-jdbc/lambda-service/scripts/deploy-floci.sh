#!/usr/bin/env bash
# Deploys lambda-service to Floci (the local AWS emulator) as:
#   IAM role -> Lambda function -> API Gateway HTTP API route GET /api/v1/lambda/config
#
#   # from: anywhere (the script finds its own files)
#   ./scripts/deploy-floci.sh
#
# Safe to re-run: every step creates the resource the first time and updates it afterwards.
# Needs: Floci running (`floci start`), the AWS CLI, Node.js 22 + npm, zip.
#
# The function runs in a container that Floci starts, so it reaches this version's Config
# Server through the host: http://host.docker.internal:8898 (Docker Desktop provides that name).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

FUNCTION=config-jdbc-lambda-service
API_NAME="$FUNCTION-api"
ROLE=config-jdbc-lambda-service-role
CONFIG_SERVER_URL=${CONFIG_SERVER_URL:-http://host.docker.internal:8898}
CONFIG_CLIENT_USERNAME=${CONFIG_CLIENT_USERNAME:-config-client}
CONFIG_CLIENT_PASSWORD=${CONFIG_CLIENT_PASSWORD:-client-secret}

export AWS_ENDPOINT_URL=${AWS_ENDPOINT_URL:-http://localhost:4566}
export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-test}
export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-test}
export AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-us-east-1}

if ! curl -sf -o /dev/null "$AWS_ENDPOINT_URL/_floci/health"; then
  echo "Floci is not reachable at $AWS_ENDPOINT_URL - start it with: floci start" >&2
  exit 1
fi

# ---- 1. package: production dependencies only, exactly as pinned in package-lock.json
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
cp "$HERE/package.json" "$HERE/package-lock.json" "$BUILD/"
cp -R "$HERE/src" "$BUILD/src"
(cd "$BUILD" && npm ci --omit=dev --ignore-scripts --silent && zip -q -r function.zip package.json src node_modules)
echo "1/4 packaged function.zip ($(du -h "$BUILD/function.zip" | cut -f1))"

# ---- 2. least-privilege role: Lambda may assume it, and it may only write its own logs
if ! ROLE_ARN=$(aws iam get-role --role-name "$ROLE" --query Role.Arn --output text 2>/dev/null); then
  ROLE_ARN=$(aws iam create-role --role-name "$ROLE" --query Role.Arn --output text \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}')
  aws iam attach-role-policy --role-name "$ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
fi
echo "2/4 role $ROLE"

# ---- 3. function
ENVIRONMENT="Variables={CONFIG_SERVER_URL=$CONFIG_SERVER_URL,CONFIG_CLIENT_USERNAME=$CONFIG_CLIENT_USERNAME,CONFIG_CLIENT_PASSWORD=$CONFIG_CLIENT_PASSWORD}"
if aws lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
  aws lambda update-function-code --function-name "$FUNCTION" --zip-file "fileb://$BUILD/function.zip" >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION"
  aws lambda update-function-configuration --function-name "$FUNCTION" \
    --handler src/handler.handler --environment "$ENVIRONMENT" >/dev/null
else
  aws lambda create-function --function-name "$FUNCTION" \
    --runtime nodejs22.x --handler src/handler.handler --role "$ROLE_ARN" \
    --zip-file "fileb://$BUILD/function.zip" --timeout 10 --memory-size 256 \
    --environment "$ENVIRONMENT" >/dev/null
fi
aws lambda wait function-active --function-name "$FUNCTION"
FUNCTION_ARN=$(aws lambda get-function --function-name "$FUNCTION" --query Configuration.FunctionArn --output text)
echo "3/4 function $FUNCTION (Config Server: $CONFIG_SERVER_URL)"

# ---- 4. API Gateway HTTP API: GET /api/v1/lambda/config -> the function
API_ID=$(aws apigatewayv2 get-apis --query "Items[?Name=='$API_NAME'].ApiId | [0]" --output text)
if [ "$API_ID" = "None" ] || [ -z "$API_ID" ]; then
  API_ID=$(aws apigatewayv2 create-api --name "$API_NAME" --protocol-type HTTP --query ApiId --output text)
  INTEGRATION_ID=$(aws apigatewayv2 create-integration --api-id "$API_ID" --integration-type AWS_PROXY \
    --integration-uri "$FUNCTION_ARN" --payload-format-version 2.0 --query IntegrationId --output text)
  aws apigatewayv2 create-route --api-id "$API_ID" --route-key 'GET /api/v1/lambda/config' \
    --target "integrations/$INTEGRATION_ID" >/dev/null
  aws apigatewayv2 create-stage --api-id "$API_ID" --stage-name '$default' --auto-deploy >/dev/null
  aws lambda add-permission --function-name "$FUNCTION" --statement-id apigateway-invoke \
    --action lambda:InvokeFunction --principal apigateway.amazonaws.com >/dev/null
fi
echo "4/4 API Gateway $API_NAME"
echo
echo "lambda-service is live:"
echo "  curl $AWS_ENDPOINT_URL/_aws/execute-api/$API_ID/\$default/api/v1/lambda/config"
