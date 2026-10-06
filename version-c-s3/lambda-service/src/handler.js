// AWS Lambda handler behind an API Gateway HTTP API (payload format 2.0). Routes:
//   GET /api/v1/lambda/config - the lambda.* configuration from the Config Server
// Every response carries security headers; every error is application/problem+json and never
// contains a stack trace. Deployed to Floci by scripts/deploy-floci.sh.
import { randomUUID } from 'node:crypto';
import { loadLambdaConfig } from './config/config-loader.js';
import { loadSettings } from './config/settings.js';
import { problem } from './http/problem.js';
import { logger } from './logger.js';

const ROUTE = '/api/v1/lambda/config';

export const SECURITY_HEADERS = Object.freeze({
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'Cache-Control': 'no-store',
});

function respond(statusCode, body, contentType = 'application/json', extraHeaders = {}) {
  return {
    statusCode,
    headers: { ...SECURITY_HEADERS, 'Content-Type': contentType, ...extraHeaders },
    body: JSON.stringify(body),
  };
}

function problemResponse(status, title, detail, extra = {}, headers = {}) {
  return respond(status, problem(status, title, detail, extra), 'application/problem+json', headers);
}

export function createHandler(loadConfig = (settings) => loadLambdaConfig(settings), env = process.env) {
  return async (event = {}) => {
    // Direct invocation (aws lambda invoke) carries no HTTP details: treat it as GET on the route.
    const path = event.rawPath ?? ROUTE;
    const method = event.requestContext?.http?.method ?? 'GET';
    if (path !== ROUTE) {
      return problemResponse(404, 'Resource not found', `No endpoint ${path}`);
    }
    if (method !== 'GET' && method !== 'HEAD') {
      return problemResponse(405, 'Method not allowed', `Request method '${method}' is not supported`, {}, { Allow: 'GET, HEAD' });
    }
    try {
      return respond(200, await loadConfig(loadSettings(env)));
    } catch (error) {
      const errorId = randomUUID();
      logger.error('Could not load the configuration', { errorId, error: error.message });
      return problemResponse(503, 'Configuration unavailable', 'The configuration could not be loaded. Try again shortly.', { errorId });
    }
  };
}

export const handler = createHandler();
