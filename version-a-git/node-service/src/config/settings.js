// Reads the service's own startup settings from environment variables and rejects bad values
// immediately, so a typo fails at startup instead of at the first request.

function integer(env, name, fallback, min, max) {
  const raw = env[name] ?? String(fallback);
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}, got "${raw}"`);
  }
  return value;
}

function url(env, name, fallback) {
  const raw = env[name] ?? fallback;
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error(`${name} must be a URL, got "${raw}"`);
  }
  if (parsed.username || parsed.password) {
    throw new Error(`${name} must not contain credentials; use the *_USERNAME / *_PASSWORD variables`);
  }
  return raw.replace(/\/$/, '');
}

export function loadSettings(env = process.env) {
  const port = integer(env, 'PORT', 8084, 1, 65535);
  return Object.freeze({
    application: 'node-service',
    port,
    configServer: Object.freeze({
      url: url(env, 'CONFIG_SERVER_URL', 'http://localhost:8888'),
      username: env.CONFIG_CLIENT_USERNAME ?? 'config-client',
      password: env.CONFIG_CLIENT_PASSWORD ?? 'client-secret',
      label: env.CONFIG_LABEL ?? 'main',
      profile: env.CONFIG_PROFILE ?? 'default',
      timeoutMs: integer(env, 'CONFIG_TIMEOUT_MS', 5000, 100, 60000),
    }),
    rabbitmq: Object.freeze({
      host: env.RABBITMQ_HOST ?? 'localhost',
      port: integer(env, 'RABBITMQ_PORT', 5672, 1, 65535),
      username: env.RABBITMQ_USERNAME ?? 'guest',
      password: env.RABBITMQ_PASSWORD ?? 'guest',
    }),
  });
}
