// RFC 9457 "problem details" error responses (application/problem+json), the same format the
// Spring Boot services return.

export function sendProblem(response, status, title, detail, extra = {}) {
  const body = {
    type: `urn:problem:${title.toLowerCase().replaceAll(' ', '-')}`,
    title,
    status,
    detail,
    timestamp: new Date().toISOString(),
    ...extra,
  };
  response.writeHead(status, { 'Content-Type': 'application/problem+json' });
  response.end(JSON.stringify(body));
}
