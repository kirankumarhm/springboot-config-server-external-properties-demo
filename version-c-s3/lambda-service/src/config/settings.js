// Reads the function's settings from its Lambda environment variables (set by
// scripts/deploy-floci.sh) and rejects bad values on the first invocation.

function integer(env, name, fallback, min, max) {
  const raw = env[name] ?? String(fallback);
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}, got "${raw}"`);
  }
  return value;
}

export function loadSettings(env = process.env) {
  const url = (env.CONFIG_SERVER_URL ?? 'http://localhost:8888').replace(/\/$/, '');
  const parsed = new URL(url);
  if (parsed.username || parsed.password) {
    throw new Error('CONFIG_SERVER_URL must not contain credentials; use CONFIG_CLIENT_USERNAME / CONFIG_CLIENT_PASSWORD');
  }
  return Object.freeze({
    application: 'lambda-service',
    configServer: Object.freeze({
      url,
      username: env.CONFIG_CLIENT_USERNAME ?? 'config-client',
      password: env.CONFIG_CLIENT_PASSWORD ?? 'client-secret',
      label: env.CONFIG_LABEL ?? 'main',
      profile: env.CONFIG_PROFILE ?? 'default',
      timeoutMs: integer(env, 'CONFIG_TIMEOUT_MS', 5000, 100, 60000),
    }),
  });
}
