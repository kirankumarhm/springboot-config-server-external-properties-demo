// RFC 9457 "problem details" bodies (application/problem+json), the same format the Spring Boot
// services return.

export function problem(status, title, detail, extra = {}) {
  return {
    type: `urn:problem:${title.toLowerCase().replaceAll(' ', '-')}`,
    title,
    status,
    detail,
    timestamp: new Date().toISOString(),
    ...extra,
  };
}
