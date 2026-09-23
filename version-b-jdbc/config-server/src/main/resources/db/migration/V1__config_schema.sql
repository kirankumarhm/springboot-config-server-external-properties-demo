-- Version B schema. The database is the system of record for configuration.
--
-- Column names and types are dictated by JdbcEnvironmentRepository's default queries:
--   sql                 : SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE=? and LABEL=?
--   sql-without-profile : SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE is null and LABEL=?
-- Extra columns are safe because those statements select named columns, never SELECT *.

CREATE TABLE properties (
    id          BIGSERIAL     PRIMARY KEY,
    application VARCHAR(128)  NOT NULL,
    -- NULL means "applies regardless of profile" - the equivalent of foo.yml rather than
    -- foo-dev.yml. It must be a real NULL: the shipped query says "PROFILE is null", so a row
    -- written as 'default' or '' is simply never found.
    profile     VARCHAR(128)  NULL,
    label       VARCHAR(128)  NOT NULL DEFAULT 'main',
    -- Lowercase, quoted. NOTE: the shipped default SQL selects "KEY"/"VALUE" in UPPERCASE, and
    -- PostgreSQL double quotes are case-SENSITIVE, so those defaults would NOT match these
    -- columns. application.yml therefore overrides both sql statements. The alternative is to
    -- declare the columns as "KEY"/"VALUE" and keep the defaults, at the cost of forcing every
    -- hand-written operator query to quote them in uppercase too.
    "key"       VARCHAR(512)  NOT NULL,
    "value"     TEXT          NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by  VARCHAR(128)  NOT NULL DEFAULT current_user,

    CONSTRAINT properties_profile_not_blank CHECK (profile IS NULL OR profile <> ''),

    -- NULLS NOT DISTINCT requires PostgreSQL 15+. Without it, a plain UNIQUE treats NULLs as
    -- distinct, so EVERY profile-independent row (profile IS NULL) escapes the uniqueness check
    -- and duplicate keys become possible, with the winner decided by physical row order.
    CONSTRAINT uq_properties UNIQUE NULLS NOT DISTINCT (application, profile, label, "key")
);

CREATE INDEX idx_properties_lookup ON properties (application, profile, label);

-- Replaces `git log`: who changed what, when, and from what to what.
CREATE TABLE properties_history (
    history_id  BIGSERIAL    PRIMARY KEY,
    operation   CHAR(1)      NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by  VARCHAR(128) NOT NULL DEFAULT current_user,
    application VARCHAR(128) NOT NULL,
    profile     VARCHAR(128) NULL,
    label       VARCHAR(128) NOT NULL,
    "key"       VARCHAR(512) NOT NULL,
    old_value   TEXT         NULL,
    new_value   TEXT         NULL
);

CREATE INDEX idx_properties_history_app ON properties_history (application, changed_at DESC);

-- Monotonic per-application revision, powering the polling reconciler.
CREATE TABLE config_revision (
    application VARCHAR(128) PRIMARY KEY,
    revision    BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Seed data, mirroring version A's config-repo exactly so all three versions
-- serve identical configuration and share one acceptance suite.
--
-- application='application' is the literal string the repository prepends to every lookup;
-- it is the DB equivalent of application.yml and applies to all clients.
-- ---------------------------------------------------------------------------
INSERT INTO properties (application, profile, label, "key", "value") VALUES
    ('application',       NULL, 'main', 'demo.shared.banner-message',      'Configured centrally via Spring Cloud Config - PostgreSQL backend'),
    ('application',       NULL, 'main', 'demo.shared.environment-label',   'local'),
    ('inventory-service', NULL, 'main', 'inventory.warehouse-code',        'WH-BLR-01'),
    ('inventory-service', NULL, 'main', 'inventory.max-order-quantity',    '500'),
    ('inventory-service', NULL, 'main', 'inventory.express-shipping-enabled', 'false'),
    ('inventory-service', NULL, 'main', 'inventory.low-stock-threshold',   '25'),
    ('pricing-service',   NULL, 'main', 'pricing.currency',                'INR'),
    ('pricing-service',   NULL, 'main', 'pricing.discount-percentage',     '10.0'),
    ('pricing-service',   NULL, 'main', 'pricing.surge-pricing-enabled',   'false'),
    ('pricing-service',   NULL, 'main', 'pricing.surge-multiplier',        '1.5');

INSERT INTO config_revision (application, revision) VALUES
    ('application', 1), ('inventory-service', 1), ('pricing-service', 1);
