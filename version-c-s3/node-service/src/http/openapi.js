// The OpenAPI 3 description of this service's API, served at GET /v3/api-docs (the same path
// springdoc uses in the Spring Boot services). Paste it into https://editor.swagger.io to browse.

const problem = {
  description: 'Error, as RFC 9457 problem details',
  content: { 'application/problem+json': { schema: { $ref: '#/components/schemas/Problem' } } },
};

export const openApiDocument = Object.freeze({
  openapi: '3.0.3',
  info: {
    title: 'Node Service API',
    version: '1.0.0',
    description:
      'Returns the node.* configuration this service is currently using. The values come from ' +
      'Spring Cloud Config Server and change live, without a restart, over Spring Cloud Bus.',
    license: { name: 'Apache 2.0', url: 'https://www.apache.org/licenses/LICENSE-2.0' },
  },
  paths: {
    '/api/v1/node/config': {
      get: {
        tags: ['Node configuration'],
        summary: 'Get the current node configuration',
        operationId: 'getNodeConfig',
        responses: {
          200: {
            description: 'The configuration currently in use',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/NodeConfig' } } },
          },
          404: problem,
          405: problem,
          500: problem,
        },
      },
    },
  },
  components: {
    schemas: {
      NodeConfig: {
        type: 'object',
        required: ['greeting', 'featureEnabled', 'maxItems'],
        properties: {
          greeting: { type: 'string', example: 'Hello from Node.js' },
          featureEnabled: { type: 'boolean', example: true },
          maxItems: { type: 'integer', minimum: 1, maximum: 1000, example: 25 },
        },
      },
      Problem: {
        type: 'object',
        properties: {
          type: { type: 'string' },
          title: { type: 'string' },
          status: { type: 'integer' },
          detail: { type: 'string' },
          timestamp: { type: 'string', format: 'date-time' },
        },
      },
    },
  },
});
