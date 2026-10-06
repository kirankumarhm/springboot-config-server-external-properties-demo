import assert from 'node:assert/strict';
import http from 'node:http';
import { test } from 'node:test';
import { createHandler } from '../src/handler.js';

const config = { greeting: 'Hi', featureEnabled: true, maxItems: 10 };
const httpEvent = (method, rawPath) => ({ rawPath, requestContext: { http: { method } } });

test('GET /api/v1/lambda/config returns only the lambda configuration, with security headers', async () => {
  const response = await createHandler(async () => config)(httpEvent('GET', '/api/v1/lambda/config'));
  assert.equal(response.statusCode, 200);
  assert.deepEqual(JSON.parse(response.body), config);
  assert.equal(response.headers['X-Frame-Options'], 'DENY');
  assert.equal(response.headers['Content-Type'], 'application/json');
});

test('a direct invocation (no HTTP details) is treated as GET on the route', async () => {
  const response = await createHandler(async () => config)({});
  assert.equal(response.statusCode, 200);
});

test('unknown paths and write methods return problem details', async () => {
  const handler = createHandler(async () => config);
  const notFound = await handler(httpEvent('GET', '/nope'));
  assert.equal(notFound.statusCode, 404);
  assert.equal(notFound.headers['Content-Type'], 'application/problem+json');

  const notAllowed = await handler(httpEvent('POST', '/api/v1/lambda/config'));
  assert.equal(notAllowed.statusCode, 405);
  assert.equal(notAllowed.headers.Allow, 'GET, HEAD');
});

test('a Config Server failure is a 503 with an errorId and no internals', async () => {
  const handler = createHandler(async () => { throw new Error('password=secret'); });
  const response = await handler(httpEvent('GET', '/api/v1/lambda/config'));
  const body = JSON.parse(response.body);
  assert.equal(response.statusCode, 503);
  assert.ok(body.errorId);
  assert.ok(!response.body.includes('secret'));
});

test('each invocation reads the latest configuration through cloud-config-client', async (t) => {
  let maxItems = 10;
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({
      name: 'lambda-service',
      propertySources: [{ name: 'lambda-service.yml', source: { 'lambda.greeting': 'Hi', 'lambda.feature-enabled': true, 'lambda.max-items': maxItems } }],
    }));
  });
  await new Promise((resolve) => server.listen(0, resolve));
  t.after(() => server.close());
  const handler = createHandler(undefined, { CONFIG_SERVER_URL: `http://localhost:${server.address().port}` });

  assert.equal(JSON.parse((await handler({})).body).maxItems, 10);
  maxItems = 99;
  assert.equal(JSON.parse((await handler({})).body).maxItems, 99);
});
