import assert from 'node:assert/strict';
import http from 'node:http';
import { test } from 'node:test';
import { createRequestHandler } from '../src/http/app.js';

async function start(t, store) {
  const server = http.createServer(createRequestHandler(store));
  await new Promise((resolve) => server.listen(0, resolve));
  t.after(() => server.close());
  return `http://localhost:${server.address().port}`;
}

const store = { current: { greeting: 'Hi', featureEnabled: true, maxItems: 25 } };

test('GET /api/v1/node/config returns only the node configuration, with security headers', async (t) => {
  const response = await fetch(`${await start(t, store)}/api/v1/node/config`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), store.current);
  assert.equal(response.headers.get('x-frame-options'), 'DENY');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.ok(response.headers.get('content-security-policy'));
});

test('serves the OpenAPI document and a health check', async (t) => {
  const base = await start(t, store);
  assert.equal((await (await fetch(`${base}/v3/api-docs`)).json()).openapi, '3.0.3');
  assert.deepEqual(await (await fetch(`${base}/health`)).json(), { status: 'UP' });
});

test('unknown paths and write methods return problem details', async (t) => {
  const base = await start(t, store);
  const notFound = await fetch(`${base}/nope`);
  assert.equal(notFound.status, 404);
  assert.equal(notFound.headers.get('content-type'), 'application/problem+json');
  assert.equal((await notFound.json()).title, 'Resource not found');

  const notAllowed = await fetch(`${base}/api/v1/node/config`, { method: 'POST' });
  assert.equal(notAllowed.status, 405);
  assert.equal(notAllowed.headers.get('allow'), 'GET, HEAD');

  const head = await fetch(`${base}/api/v1/node/config`, { method: 'HEAD' });
  assert.equal(head.status, 200);
});

test('an unexpected error returns 500 with an errorId and no internals', async (t) => {
  const broken = { get current() { throw new Error('secret detail'); } };
  const response = await fetch(`${await start(t, broken)}/api/v1/node/config`);
  const body = await response.json();
  assert.equal(response.status, 500);
  assert.ok(body.errorId);
  assert.ok(!JSON.stringify(body).includes('secret detail'));
});
