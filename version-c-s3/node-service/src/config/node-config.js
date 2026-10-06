// Turns the raw properties from the Config Server into this service's typed, validated
// configuration. Only node.* keys are used; everything else (for example the shared
// application.yml) is ignored.
//
// Values may arrive as strings (the JDBC backend stores everything as text) or as real
// booleans and numbers (the Git and S3 backends parse YAML), so both are accepted.

export class InvalidConfigurationError extends Error {
  constructor(problems) {
    super(`Invalid node-service configuration: ${problems.join('; ')}`);
    this.name = 'InvalidConfigurationError';
    this.problems = problems;
  }
}

export function toNodeConfig(get) {
  const problems = [];

  const greeting = get('node.greeting');
  if (typeof greeting !== 'string' || greeting.trim() === '') {
    problems.push('node.greeting must be a non-empty string');
  }

  const rawFlag = get('node.feature-enabled');
  const featureEnabled = rawFlag === true || rawFlag === 'true';
  if (!(featureEnabled || rawFlag === false || rawFlag === 'false')) {
    problems.push('node.feature-enabled must be true or false');
  }

  const maxItems = Number(get('node.max-items'));
  if (!Number.isInteger(maxItems) || maxItems < 1 || maxItems > 1000) {
    problems.push('node.max-items must be an integer between 1 and 1000');
  }

  if (problems.length > 0) {
    throw new InvalidConfigurationError(problems);
  }
  return Object.freeze({ greeting, featureEnabled, maxItems });
}
