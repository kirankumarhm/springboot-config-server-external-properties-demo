import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isForThisInstance } from '../src/bus/destination.js';

const busId = 'node-service:8084:abc123';

test('matches broadcasts to everyone', () => {
  assert.ok(isForThisInstance('**', busId));
  assert.ok(isForThisInstance(undefined, busId));
});

test('matches this application, with or without the instance part', () => {
  assert.ok(isForThisInstance('node-service:**', busId));
  assert.ok(isForThisInstance('node-service', busId));
  assert.ok(isForThisInstance('node-service:8084:*', busId));
});

test('ignores other applications and prefixes of our name', () => {
  assert.equal(isForThisInstance('go-service:**', busId), false);
  assert.equal(isForThisInstance('node:**', busId), false);
  assert.equal(isForThisInstance('node-service:9999:*', busId), false);
});
