--liquibase formatted sql

-- The safety net for the one redaction marker that was ever persisted.
--
-- user-settings stored '[redacted]' in old_value and new_value, so choosing a single platform marker
-- was a data migration and not a constant change: without it, the table spells one concept two ways
-- and no query can tell "redacted under the old rule" from "a user whose setting value is literally
-- the string [redacted]". The rest-client's '****' and the hot-reload module's '***REDACTED***' were
-- only ever logged, so they need nothing.
--
-- 0002 already rewrites the marker as it migrates each row, so on a deployment that adopts this
-- module at this release there is nothing here to do. This changeset exists for a deployment that ran
-- an earlier release of 0002, which migrated the marker verbatim - and it is runAlways for the same
-- reason 0002 is.
--
-- Applied to the migrated attributes rather than to the old table, which is left untouched so a
-- deployment can verify the migration against it before dropping it.

--changeset ludwig-audit:audit-0004-normalise-redaction-marker dbms:postgresql runAlways:true
--comment Rewrites the legacy '[redacted]' marker to the platform's single mask.
UPDATE audit_event
   SET attributes = jsonb_strip_nulls(
           attributes
           || CASE WHEN attributes->>'oldValue' = '[redacted]'
                   THEN jsonb_build_object('oldValue', '***REDACTED***')
                   ELSE '{}'::jsonb END
           || CASE WHEN attributes->>'newValue' = '[redacted]'
                   THEN jsonb_build_object('newValue', '***REDACTED***')
                   ELSE '{}'::jsonb END)
 WHERE category = 'settings'
   AND (attributes->>'oldValue' = '[redacted]' OR attributes->>'newValue' = '[redacted]');
--rollback UPDATE audit_event
--rollback    SET attributes = jsonb_strip_nulls(
--rollback            attributes
--rollback            || CASE WHEN attributes->>'oldValue' = '***REDACTED***'
--rollback                    THEN jsonb_build_object('oldValue', '[redacted]')
--rollback                    ELSE '{}'::jsonb END
--rollback            || CASE WHEN attributes->>'newValue' = '***REDACTED***'
--rollback                    THEN jsonb_build_object('newValue', '[redacted]')
--rollback                    ELSE '{}'::jsonb END)
--rollback  WHERE category = 'settings'
--rollback    AND source_system = 'migrated:user-settings';
