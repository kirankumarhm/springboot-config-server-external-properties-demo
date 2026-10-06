// Decides whether a Spring Cloud Bus message is addressed to this instance, using the same rule
// as Spring's ServiceMatcher: the message's destinationService is an Ant-style pattern matched
// against our bus id "<application>:<port>:<unique id>", with ":" as the separator.
//   "**"                  -> every instance of every application (POST /actuator/busrefresh)
//   "node-service:**"     -> every instance of node-service
//   "node-service:8084:*" -> instances listening on port 8084

function patternToRegExp(pattern) {
  const source = pattern
    .split(':')
    .map((segment) => (segment === '**' ? '.*' : segment.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '[^:]*')))
    .join(':')
    .replace(/:\.\*$/, '(:.*)?'); // "app:**" also matches a bare "app"
  return new RegExp(`^${source}$`);
}

export function isForThisInstance(destination, busId) {
  if (!destination || destination.trim() === '' || destination === '**') {
    return true;
  }
  return patternToRegExp(destination).test(busId) || patternToRegExp(`${destination}:**`).test(busId);
}
