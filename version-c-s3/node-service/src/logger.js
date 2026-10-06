// Structured JSON logging: one object per line on stdout, which Docker and Kubernetes collect.
// Never pass secrets (passwords, Authorization headers) to these functions.

function write(level, message, fields = {}) {
  const entry = { time: new Date().toISOString(), level, service: 'node-service', message, ...fields };
  const line = JSON.stringify(entry);
  if (level === 'error') {
    process.stderr.write(`${line}\n`);
  } else {
    process.stdout.write(`${line}\n`);
  }
}

export const logger = {
  info: (message, fields) => write('info', message, fields),
  warn: (message, fields) => write('warn', message, fields),
  error: (message, fields) => write('error', message, fields),
};
