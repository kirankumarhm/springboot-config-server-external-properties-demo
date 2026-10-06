// Loads this function's configuration from Spring Cloud Config Server with the
// cloud-config-client library (URL building, Basic auth, Spring's property-source order).
//
// It loads on EVERY invocation. A Lambda function is frozen between invocations, so it cannot
// keep a RabbitMQ connection open for Spring Cloud Bus, and a timer would not fire while frozen.
// Loading per invocation means a change is visible on the very next call.
import http from 'node:http';
import https from 'node:https';
import client from 'cloud-config-client';
import { toLambdaConfig } from './lambda-config.js';

// Turns a load failure into one clear sentence. Node reports "localhost unreachable" as an
// AggregateError with an EMPTY message (one failure for ::1, one for 127.0.0.1), which would log
// as "error":"" - so the individual causes are listed instead.
export function describeLoadError(error, url) {
  const causes = error?.errors?.length ? error.errors.map((e) => e.message).join('; ') : error?.message || error?.code || String(error);
  const hint = /\b401\b/.test(causes) ? ' - check CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD' : '';
  return `Could not load configuration from ${url}: ${causes}${hint}`;
}

export async function loadLambdaConfig(settings, load = client.load) {
  const { url, username, password, label, profile, timeoutMs } = settings.configServer;
  const Agent = url.startsWith('https:') ? https.Agent : http.Agent;
  let config;
  try {
    config = await load({
      endpoint: url,
      name: settings.application,
      profiles: profile,
      label,
      auth: { user: username, pass: password },
      agent: new Agent({ timeout: timeoutMs }),
    });
  } catch (error) {
    throw new Error(describeLoadError(error, `${url}/${settings.application}/${profile}/${label}`));
  }
  return toLambdaConfig((key) => config.get(key));
}
