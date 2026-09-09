-- Change detection for the JDBC backend.
--
-- Unlike Git, a database never calls you when something changes, so the notification has to be
-- built. A trigger plus LISTEN/NOTIFY is used rather than an admin write API because it catches
-- EVERY writer - including a DBA running psql by hand or a migration tool - so configuration
-- cannot change invisibly.

-- ---------------------------------------------------------------- audit trail
CREATE FUNCTION fn_properties_history() RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        INSERT INTO properties_history(operation, application, profile, label, "key", old_value)
        VALUES ('D', OLD.application, OLD.profile, OLD.label, OLD."key", OLD."value");
        RETURN OLD;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO properties_history(operation, application, profile, label, "key", old_value, new_value)
        VALUES ('U', NEW.application, NEW.profile, NEW.label, NEW."key", OLD."value", NEW."value");
        RETURN NEW;
    ELSE
        INSERT INTO properties_history(operation, application, profile, label, "key", new_value)
        VALUES ('I', NEW.application, NEW.profile, NEW.label, NEW."key", NEW."value");
        RETURN NEW;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_properties_history
    AFTER INSERT OR UPDATE OR DELETE ON properties
    FOR EACH ROW EXECUTE FUNCTION fn_properties_history();

-- ---------------------------------------------------------------- notification
-- Statement-level with transition tables (PostgreSQL 10+), deliberately NOT row-level: a bulk
-- UPDATE touching 500 rows of one application then produces ONE notification instead of 500.
-- That removes the broadcast-storm risk without any debounce logic in the application.
CREATE FUNCTION fn_notify_config_change() RETURNS trigger AS $$
DECLARE
    rec RECORD;
    payload TEXT;
BEGIN
    FOR rec IN SELECT DISTINCT application FROM changed_rows LOOP
        INSERT INTO config_revision (application, revision, updated_at)
             VALUES (rec.application, 1, now())
        ON CONFLICT (application)
             DO UPDATE SET revision = config_revision.revision + 1, updated_at = now();

        SELECT json_build_object(
                   'application',   rec.application,
                   'revision',      (SELECT revision FROM config_revision WHERE application = rec.application),
                   'correlationId', md5(random()::text || clock_timestamp()::text),
                   'detectedAt',    to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
               )::text
          INTO payload;

        -- NOTIFY is transactional: the payload is delivered only if this transaction COMMITS.
        -- A rolled-back configuration change therefore never reaches a client. This is the
        -- transactional-outbox guarantee, with no outbox table to maintain.
        PERFORM pg_notify('config_changed', payload);
    END LOOP;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notify_config_insert AFTER INSERT ON properties
    REFERENCING NEW TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();

CREATE TRIGGER trg_notify_config_update AFTER UPDATE ON properties
    REFERENCING NEW TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();

CREATE TRIGGER trg_notify_config_delete AFTER DELETE ON properties
    REFERENCING OLD TABLE AS changed_rows
    FOR EACH STATEMENT EXECUTE FUNCTION fn_notify_config_change();
