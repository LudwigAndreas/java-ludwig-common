-- Makes the legacy fixture idempotent; see LegacyAuditTablesInitializer for why that is needed now that
-- the container is shared across the JVM rather than owned by one test class.
--
-- audit_event is deliberately NOT dropped here: it belongs to this module's own Liquibase changelog, which
-- runs after this script and owns its lifecycle. Dropping it would make Liquibase's DATABASECHANGELOG
-- disagree with the schema, and the next context in the same JVM would fail on a CREATE for a changeset it
-- has already recorded as applied.
DROP TABLE IF EXISTS user_setting_audit;
DROP TABLE IF EXISTS sync_audit_record;
