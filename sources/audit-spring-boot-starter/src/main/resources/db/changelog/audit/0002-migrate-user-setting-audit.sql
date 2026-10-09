--liquibase formatted sql

-- ======================================================================================
-- MIGRATION OF THE TWO LEGACY TRAILS - 1 of 2
--
-- Both this and 0003 are guarded by a table-exists precondition, because these changesets ship to
-- every consumer: a service that never used user-settings or reconciliation has no such table, and a
-- migration that failed there would make this changelog unusable for it.
--
-- Both are also runAlways:true, and that is not belt and braces - without it the migration silently
-- moves nothing. Ordering between two SpringLiquibase beans is not the order their autoconfigurations
-- are registered in: measured, this changelog runs BEFORE both the consuming application's own
-- changelog and the other modules', so on the boot that introduces this module neither legacy table
-- exists yet. A changeset marked as ran is never reconsidered, so a one-shot precondition would mark
-- these as ran against an absent table and no row would ever migrate, with nothing to say so.
-- runAlways makes the changeset re-evaluate its precondition on every boot, and the statements are
-- idempotent (WHERE NOT EXISTS), so it moves rows on the first boot where the table is there and is a
-- no-op afterwards.
--
-- The cost is one anti-join per boot against a legacy table. It is bounded and it is temporary: once
-- a deployment has verified the migration and dropped the legacy table, the precondition fails and
-- the statement is not run at all. That is the intended end state - see this module's README.
--
-- The row-moving statement is a set-based INSERT ... SELECT because that is the only way to move an
-- unknown number of rows in one statement - Liquibase's <insert> took literal values, which is one of
-- the reasons this repository now authors every changeset as SQL. A changelog is not Java: the
-- repository's "QueryDSL only" rule is about application code, which is why the read side of this
-- table in AuditEventQueryRepositoryImpl has no SQL in it at all.
--
-- Moves user_setting_audit into audit_event. A genuine audit trail, so it migrates rather than
-- staying in a table nothing fills: a consolidation that starts the new trail empty has moved the
-- auditor's problem rather than solved it.
--
-- The mapping, and what is not lost:
--   - action becomes 'setting.set' / 'setting.reset' / 'setting.admin-read', keeping the distinction
--     the SettingAuditAction enum carried;
--   - subject becomes on_behalf_of and actor becomes actor_subject, which is the direction the old
--     columns actually meant - 'subject' was whose setting changed, not who changed it;
--   - tenant_id, setting_key, category, scope_type, scope_id, old_value, new_value and redacted all
--     become attributes, because they are what that category's events are about and nothing else in
--     the platform has a column for them;
--   - the old redaction marker '[redacted]' is rewritten to the platform's, inline, which is the whole
--     reason picking one marker was a data migration rather than a constant change. It is done here as
--     well as in 0004 on purpose: this statement is where a row is authored, so a freshly migrated
--     row is never briefly wrong, and 0004 is then only the safety net for rows moved by an earlier
--     release of this changeset.
--
-- imported_at is set to occurred_at rather than now(): the row is not being authored now, and a reader
-- comparing the two columns on a migrated row should see that nothing was relayed.
-- ======================================================================================

--changeset ludwig-audit:audit-0002-migrate-user-setting-audit dbms:postgresql runAlways:true
--comment Moves user_setting_audit into audit_event.
--preconditions onFail:MARK_RAN
--precondition-table-exists table:user_setting_audit
INSERT INTO audit_event (
    id, imported_at, source_system, occurred_at, category, action,
    actor_subject, actor_type, on_behalf_of,
    resource_type, resource_id, outcome, correlation_id, attributes)
SELECT
    a.id,
    a.occurred_at,
    'migrated:user-settings',
    a.occurred_at,
    'settings',
    CASE a.action
        WHEN 'SET' THEN 'setting.set'
        WHEN 'RESET' THEN 'setting.reset'
        WHEN 'ADMIN_READ' THEN 'setting.admin-read'
        ELSE 'setting.' || lower(a.action)
    END,
    a.actor,
    NULL,
    a.subject,
    'setting',
    a.setting_key,
    'SUCCESS',
    a.correlation_id,
    jsonb_strip_nulls(jsonb_build_object(
        'tenantId', a.tenant_id,
        'settingKey', a.setting_key,
        'settingCategory', a.category,
        'scopeType', a.scope_type,
        'scopeId', a.scope_id,
        'oldValue', CASE WHEN a.old_value = '[redacted]'
                         THEN '***REDACTED***' ELSE a.old_value END,
        'newValue', CASE WHEN a.new_value = '[redacted]'
                         THEN '***REDACTED***' ELSE a.new_value END,
        'redacted', a.redacted))
FROM user_setting_audit a
WHERE NOT EXISTS (SELECT 1 FROM audit_event e WHERE e.id = a.id);
--rollback DELETE FROM audit_event WHERE source_system = 'migrated:user-settings'
