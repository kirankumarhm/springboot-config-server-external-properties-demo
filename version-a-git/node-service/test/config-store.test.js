import assert from 'node:assert/strict';
import http from 'node:http';
import { test } from 'node:test';
import { ConfigStore } from '../src/config/config-store.js';

// A stand-in Config Server: answers GET /node-service/default/main with Basic auth required.
async function fakeConfigServer(t, sources) {
  const expected = `Basic ${Buffer.from('config-client:secret').toString('base64')}`;
  const server = http.createServer((request, response) => {
    if (request.headers.authorization !== expected || request.url !== '/node-service/default/main') {
      response.writeHead(401).end();
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ name: 'node-service', propertySources: sources() }));
  });
  await new Promise((resolve) => server.listen(0, resolve));
  t.after(() => server.close());
  return `http://localhost:${server.address().port}`;
}

const settings = (url, password = 'secret') => ({
  application: 'node-service',
  configServer: { url, username: 'config-client', password, label: 'main', profile: 'default', timeoutMs: 2000 },
});

test('loads through cloud-config-client; the most specific property source wins', async (t) => {
  const url = await fakeConfigServer(t, () => [
    { name: 'node-service.yml', source: { 'node.greeting': 'Hello', 'node.feature-enabled': true, 'node.max-items': 25 } },
    { name: 'application.yml', source: { 'node.max-items': 1, 'demo.shared.environment-label': 'x' } },
  ]);
  const store = new ConfigStore(settings(url));
  await store.load();
  assert.deepEqual(store.current, { greeting: 'Hello', featureEnabled: true, maxItems: 25 });
});

test('startup fails on wrong credentials', async (t) => {
  const url = await fakeConfigServer(t, () => []);
  await assert.rejects(new ConfigStore(settings(url, 'wrong')).load());
});

test('a failed refresh keeps the values already in use', async (t) => {
  let maxItems = 25;
  const url = await fakeConfigServer(t, () => [
    { name: 'node-service.yml', source: { 'node.greeting': 'Hi', 'node.feature-enabled': true, 'node.max-items': maxItems } },
  ]);
  const store = new ConfigStore(settings(url));
  await store.load();

  maxItems = 99;
  await store.refresh('test');
  assert.equal(store.current.maxItems, 99);

  maxItems = 0; // invalid
  await store.refresh('test');
  assert.equal(store.current.maxItems, 99);
});

test('a failure names the URL and the cause, never an empty message', async (t) => {
  const closed = http.createServer();
  await new Promise((resolve) => closed.listen(0, resolve));
  const { port } = closed.address();
  await new Promise((resolve) => closed.close(resolve));
  await assert.rejects(
    new ConfigStore(settings(`http://localhost:${port}`)).load(),
    new RegExp(`Could not load configuration from http://localhost:${port}/node-service/default/main: .*ECONNREFUSED`),
  );

  const url = await fakeConfigServer(t, () => []);
  await assert.rejects(new ConfigStore(settings(url, 'wrong')).load(), /401 - check CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD/);
});
