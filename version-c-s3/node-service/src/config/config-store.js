// Loads this service's configuration from Spring Cloud Config Server with the
// cloud-config-client library, and holds the current values.
//
// cloud-config-client builds the URL ({url}/{application}/{profile}/{label}), sends Basic auth,
// and merges the property sources in Spring's order (the most specific source wins).
import http from 'node:http';
import https from 'node:https';
import client from 'cloud-config-client';
import { logger } from '../logger.js';
import { toNodeConfig } from './node-config.js';

// Turns a load failure into one clear sentence. Node reports "localhost unreachable" as an
// AggregateError with an EMPTY message (one failure for ::1, one for 127.0.0.1), which would log
// as "error":"" - so the individual causes are listed instead.
export function describeLoadError(error, url) {
  const causes = error?.errors?.length ? error.errors.map((e) => e.message).join('; ') : error?.message || error?.code || String(error);
  const hint = /\b401\b/.test(causes) ? ' - check CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD' : '';
  return `Could not load configuration from ${url}: ${causes}${hint}`;
}

export class ConfigStore {
  #settings;
  #load;
  #current;

  constructor(settings, load = client.load) {
    this.#settings = settings;
    this.#load = load;
  }

  get current() {
    return this.#current;
  }

  // Fetches and validates. Throws on any failure, leaving the current values untouched.
  async #fetch() {
    const { url, username, password, label, profile, timeoutMs } = this.#settings.configServer;
    const Agent = url.startsWith('https:') ? https.Agent : http.Agent;
    let config;
    try {
      config = await this.#load({
        endpoint: url,
        name: this.#settings.application,
        profiles: profile,
        label,
        auth: { user: username, pass: password },
        agent: new Agent({ timeout: timeoutMs }),
      });
    } catch (error) {
      throw new Error(describeLoadError(error, `${url}/${this.#settings.application}/${profile}/${label}`));
    }
    return toNodeConfig((key) => config.get(key));
  }

  // At startup there is nothing to fall back on, so any failure is fatal (the caller exits).
  async load() {
    this.#current = await this.#fetch();
    logger.info('Configuration loaded', { config: this.#current });
  }

  // On a refresh, a failure keeps the values already in use and is logged for operators.
  async refresh(reason) {
    try {
      const latest = await this.#fetch();
      const changed = JSON.stringify(latest) !== JSON.stringify(this.#current);
      this.#current = latest;
      logger.info(changed ? 'Configuration changed' : 'Configuration unchanged', { reason, config: latest });
    } catch (error) {
      logger.error('Configuration refresh failed; keeping the values in use', { reason, error: error.message });
    }
  }
}
