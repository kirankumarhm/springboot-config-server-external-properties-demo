import assert from 'node:assert/strict';
import { test } from 'node:test';
import { InvalidConfigurationError, toLambdaConfig } from '../src/config/lambda-config.js';

const from = (values) => (key) => values[key];

test('accepts YAML-typed values (Git and S3 backends)', () => {
  const config = toLambdaConfig(from({ 'lambda.greeting': 'Hi', 'lambda.feature-enabled': true, 'lambda.max-items': 25 }));
  assert.deepEqual(config, { greeting: 'Hi', featureEnabled: true, maxItems: 25 });
});

test('accepts string values (JDBC backend)', () => {
  const config = toLambdaConfig(from({ 'lambda.greeting': 'Hi', 'lambda.feature-enabled': 'false', 'lambda.max-items': '7' }));
  assert.deepEqual(config, { greeting: 'Hi', featureEnabled: false, maxItems: 7 });
});

test('rejects invalid values and names every problem', () => {
  assert.throws(
    () => toLambdaConfig(from({ 'lambda.greeting': ' ', 'lambda.feature-enabled': 'yes', 'lambda.max-items': '0' })),
    (error) => error instanceof InvalidConfigurationError && error.problems.length === 3,
  );
});
