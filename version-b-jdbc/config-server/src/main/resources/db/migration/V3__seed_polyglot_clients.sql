-- Seed data for the three non-Spring clients: node-service (Node.js), go-service (Go) and
-- lambda-service (AWS Lambda). Same values as version A's config-repo/<name>.yml files, so all
-- three backends serve identical configuration.
--
-- A NEW migration rather than an edit to V1: Flyway stores a checksum of every migration it has
-- applied, and refuses to start if an applied file later changes.
--
-- profile is NULL ("applies to every profile"), exactly like the V1 rows. The notify trigger
-- from V2 fires for this INSERT too and creates the config_revision rows itself.
INSERT INTO properties (application, profile, label, "key", "value") VALUES
    ('node-service',   NULL, 'main', 'node.greeting',          'Hello from Node.js'),
    ('node-service',   NULL, 'main', 'node.feature-enabled',   'true'),
    ('node-service',   NULL, 'main', 'node.max-items',         '25'),
    ('go-service',     NULL, 'main', 'go.greeting',            'Hello from Go'),
    ('go-service',     NULL, 'main', 'go.feature-enabled',     'false'),
    ('go-service',     NULL, 'main', 'go.max-items',           '50'),
    ('lambda-service', NULL, 'main', 'lambda.greeting',        'Hello from AWS Lambda'),
    ('lambda-service', NULL, 'main', 'lambda.feature-enabled', 'true'),
    ('lambda-service', NULL, 'main', 'lambda.max-items',       '10');
