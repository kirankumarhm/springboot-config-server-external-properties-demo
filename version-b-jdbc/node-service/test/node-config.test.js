import assert from 'node:assert/strict';
import { test } from 'node:test';
import { InvalidConfigurationError, toNodeConfig } from '../src/config/node-config.js';

const from = (values) => (key) => values[key];

test('accepts YAML-typed values (Git and S3 backends)', () => {
  const config = toNodeConfig(from({ 'node.greeting': 'Hi', 'node.feature-enabled': true, 'node.max-items': 25 }));
  assert.deepEqual(config, { greeting: 'Hi', featureEnabled: true, maxItems: 25 });
});

test('accepts string values (JDBC backend)', () => {
  const config = toNodeConfig(from({ 'node.greeting': 'Hi', 'node.feature-enabled': 'false', 'node.max-items': '7' }));
  assert.deepEqual(config, { greeting: 'Hi', featureEnabled: false, maxItems: 7 });
});

test('rejects invalid values and names every problem', () => {
  assert.throws(
    () => toNodeConfig(from({ 'node.greeting': ' ', 'node.feature-enabled': 'yes', 'node.max-items': '0' })),
    (error) => error instanceof InvalidConfigurationError && error.problems.length === 3,
  );
});
