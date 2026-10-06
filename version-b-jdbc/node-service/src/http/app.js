// The HTTP layer. Routes:
//   GET /api/v1/node/config  - the API: the node.* configuration in use
//   GET /v3/api-docs         - the OpenAPI 3 document
//   GET /health              - liveness/readiness probe for Docker and Kubernetes
// Every response carries security headers; every error is application/problem+json.
import { randomUUID } from 'node:crypto';
import { logger } from '../logger.js';
import { openApiDocument } from './openapi.js';
import { sendProblem } from './problem.js';

const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'Cache-Control': 'no-store',
};

function sendJson(response, body) {
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}

export function createRequestHandler(store) {
  const routes = {
    '/api/v1/node/config': () => store.current,
    '/v3/api-docs': () => openApiDocument,
    '/health': () => ({ status: 'UP' }),
  };

  return (request, response) => {
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
      response.setHeader(name, value);
    }
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      const route = routes[path];
      if (!route) {
        sendProblem(response, 404, 'Resource not found', `No endpoint ${path}`);
      } else if (request.method !== 'GET' && request.method !== 'HEAD') {
        response.setHeader('Allow', 'GET, HEAD');
        sendProblem(response, 405, 'Method not allowed', `Request method '${request.method}' is not supported`);
      } else {
        sendJson(response, route());
      }
    } catch (error) {
      const errorId = randomUUID();
      logger.error('Unexpected error', { errorId, error: error.message, stack: error.stack });
      sendProblem(response, 500, 'Internal server error', 'An unexpected error occurred.', { errorId });
    }
  };
}
