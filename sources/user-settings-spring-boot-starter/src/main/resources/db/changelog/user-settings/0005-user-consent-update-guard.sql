--liquibase formatted sql

-- Belt and braces over db-core's SnapshotImmutabilityListener. The listener stops the application from
-- updating a consent row; this stops everything else - a migration script, a support engineer with
-- psql, an ORM in some future service that maps the table without the listener. Append-only is a claim
-- this module makes to auditors, and a claim enforced only in one application's object model is not
-- one worth making.
--
-- UPDATE only, deliberately not DELETE: the retention job has to be able to remove rows once their
-- statutory period has passed, and a guard that blocked that would simply be dropped when someone
-- needed it.
--
-- splitStatements:false because the function body is $$-quoted and contains its own semicolons, which
-- Liquibase's statement splitter would otherwise cut in half. rollbackSplitStatements:false for the
-- same reason is not needed - the rollback is two ordinary statements - but it is set so that the two
-- halves of this changeset are read under the same rule.

--changeset ludwig-user-settings:user-settings-0005-user-consent-update-guard dbms:postgresql splitStatements:false
--comment A database-level guard making the consent ledger append-only for every writer.
CREATE OR REPLACE FUNCTION user_consent_reject_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'user_consent is append-only: record a new decision instead of updating %', OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_consent_no_update
    BEFORE UPDATE ON user_consent
    FOR EACH ROW EXECUTE FUNCTION user_consent_reject_update();
--rollback DROP TRIGGER IF EXISTS trg_user_consent_no_update ON user_consent;
--rollback DROP FUNCTION IF EXISTS user_consent_reject_update();
