--liquibase formatted sql

-- ======================================================================================
-- MIGRATION OF THE TWO LEGACY TRAILS - 2 of 2
--
-- Same guard and same runAlways reasoning as 0002, which states it in full.
--
-- Moves sync_audit_record into audit_event. Its Category enum (RUN, RECORD, JOB, LEASE, OPERATOR) is
-- preserved as an attribute rather than as audit_event.category, because audit_event.category names
-- the subsystem and these five name a kind of event within it - collapsing them into the subsystem
-- column would lose the distinction and make the column mean two different things depending on the
-- row.
--
-- Note that nothing in the repository ever wrote this table: the entity and its repository existed and
-- the PersistingReconciliationAuditLogger its javadoc referred to did not. The migration runs anyway,
-- because a deployment may have written rows through a logger of its own and a migration that assumed
-- the table was empty would silently discard them.
-- ======================================================================================

--changeset ludwig-audit:audit-0003-migrate-sync-audit-record dbms:postgresql runAlways:true
--comment Moves sync_audit_record into audit_event.
--preconditions onFail:MARK_RAN
--precondition-table-exists table:sync_audit_record
INSERT INTO audit_event (
    id, imported_at, source_system, occurred_at, category, action,
    actor_subject, on_behalf_of, resource_type, resource_id,
    outcome, reason, correlation_id, attributes)
SELECT
    r.id,
    r.occurred_at,
    'migrated:reconciliation',
    r.occurred_at,
    'reconciliation',
    r.event,
    COALESCE(r.principal, 'system'),
    NULL,
    'sync-task',
    r.task_name,
    'SUCCESS',
    r.detail,
    r.correlation_id,
    jsonb_strip_nulls(jsonb_build_object(
        'eventCategory', r.category,
        'taskName', r.task_name,
        'subject', r.subject,
        'fromState', r.from_state,
        'toState', r.to_state,
        'runId', r.run_id::text,
        'instance', r.instance))
FROM sync_audit_record r
WHERE NOT EXISTS (SELECT 1 FROM audit_event e WHERE e.id = r.id);
--rollback DELETE FROM audit_event WHERE source_system = 'migrated:reconciliation'
