package httpapi

// openAPIDocument describes the API, served at GET /v3/api-docs (the same path springdoc uses in
// the Spring Boot services). Paste it into https://editor.swagger.io to browse it.
const openAPIDocument = `{
  "openapi": "3.0.3",
  "info": {
    "title": "Go Service API",
    "version": "1.0.0",
    "description": "Returns the go.* configuration this service is currently using. The values come from Spring Cloud Config Server and change live, without a restart, over Spring Cloud Bus.",
    "license": {"name": "Apache 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0"}
  },
  "paths": {
    "/api/v1/go/config": {
      "get": {
        "tags": ["Go configuration"],
        "summary": "Get the current go configuration",
        "operationId": "getGoConfig",
        "responses": {
          "200": {"description": "The configuration currently in use", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/GoConfig"}}}},
          "404": {"$ref": "#/components/responses/Problem"},
          "405": {"$ref": "#/components/responses/Problem"},
          "500": {"$ref": "#/components/responses/Problem"}
        }
      }
    }
  },
  "components": {
    "responses": {
      "Problem": {"description": "Error, as RFC 9457 problem details", "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}
    },
    "schemas": {
      "GoConfig": {
        "type": "object",
        "required": ["greeting", "featureEnabled", "maxItems"],
        "properties": {
          "greeting": {"type": "string", "example": "Hello from Go"},
          "featureEnabled": {"type": "boolean", "example": false},
          "maxItems": {"type": "integer", "minimum": 1, "maximum": 1000, "example": 50}
        }
      },
      "Problem": {
        "type": "object",
        "properties": {
          "type": {"type": "string"}, "title": {"type": "string"}, "status": {"type": "integer"},
          "detail": {"type": "string"}, "timestamp": {"type": "string", "format": "date-time"}
        }
      }
    }
  }
}`
