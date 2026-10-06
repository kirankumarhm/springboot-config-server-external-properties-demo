import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BusListener } from '../src/bus/bus-listener.js';

const settings = { application: 'node-service', port: 8084, rabbitmq: {} };

function listener() {
  const reasons = [];
  return { reasons, bus: new BusListener(settings, (reason) => reasons.push(reason)) };
}

test('refreshes on a RefreshRemoteApplicationEvent addressed to node-service', () => {
  const { reasons, bus } = listener();
  const handled = bus.handle(JSON.stringify({
    type: 'RefreshRemoteApplicationEvent', destinationService: 'node-service:**', originService: 'config-server:8888:x', id: 'e1',
  }));
  assert.equal(handled, true);
  assert.deepEqual(reasons, ['bus event e1']);
});

test('ignores events for other services, other event types, its own events and junk', () => {
  const { reasons, bus } = listener();
  bus.handle(JSON.stringify({ type: 'RefreshRemoteApplicationEvent', destinationService: 'go-service:**' }));
  bus.handle(JSON.stringify({ type: 'AckRemoteApplicationEvent', destinationService: '**' }));
  bus.handle(JSON.stringify({ type: 'RefreshRemoteApplicationEvent', destinationService: '**', originService: bus.busId }));
  bus.handle('not json');
  assert.deepEqual(reasons, []);
});

test('the bus id follows the Spring format <application>:<port>:<unique id>', () => {
  assert.match(listener().bus.busId, /^node-service:8084:[0-9a-f]{32}$/);
});
