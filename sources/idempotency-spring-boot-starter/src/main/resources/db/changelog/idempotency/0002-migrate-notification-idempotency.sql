--liquibase formatted sql

-- ======================================================================================
-- MIGRATION OF notification-service's notification_idempotency
--
-- Guarded by a table-exists precondition, because this changelog ships to every consumer: a service
-- that never had that table must not be unable to use this module.
--
-- runAlways:true, and that is not belt and braces - without it the migration silently moves nothing.
-- Ordering between two SpringLiquibase beans is not the order their autoconfigurations are
-- registered in, and this changelog runs before the consuming application's own. A changeset marked
-- as ran is never reconsidered, so a one-shot precondition would mark this as ran against a table
-- that is there and then never reconsider a row inserted a second later. runAlways re-evaluates on
-- every boot, and the statement is idempotent (WHERE NOT EXISTS), so it moves rows on every boot
-- where there are rows to move and is an anti-join that finds nothing afterwards.
--
-- The same shape and the same reasoning as audit-0002 and audit-0003, which moved user_setting_audit
-- and sync_audit_record into audit_event. That is not a coincidence: it is the shape a promotion out
-- of a service into a platform module takes, and doing it differently here would mean two migration
-- patterns to learn.
--
-- WHY THE ROWS MOVE AT ALL, rather than the new table starting empty: every key in the old table is
-- a claim on work that has been done. Starting empty means every in-flight key is a duplicate the
-- service will act on a second time - once, during the deploy that migrates it. For
-- notification-service that is a second email or a second chat message to a real person, which is
-- precisely the failure this module exists to prevent, arriving as a side effect of introducing it.
--
-- WHY THE OLD TABLE IS NOT DROPPED HERE: a deployment verifies the counts first - see this module's
-- README and notification-service's. Once it drops the table, this precondition fails and the
-- statement is not run at all, which is the intended end state.
--
-- The mapping, and what is not lost:
--   - scope and idempotency_key carry over unchanged, so a key claimed under the old 'kafka' or
--     'rest' scope keeps deduplicating against redeliveries that are already in flight;
--   - request_id carries over, so a duplicate arriving after the deploy is still answered with the
--     id of the request that was actually created;
--   - state becomes COMPLETED, which is what every row in the old table meant: that table had no
--     state column because its only claim mode was the transactional one, where a visible row
--     describes committed work by construction;
--   - lease_expires_at, the response columns and fingerprint are null. The old primitive had no
--     in-progress state, stored no response and computed no fingerprint, and inventing values here
--     would be worse than absent ones - a fabricated fingerprint would refuse the very retries these
--     rows exist to recognise, because ClaimResult treats a null fingerprint as "never mismatches";
--   - created_at and expires_at carry over, so a migrated claim expires exactly when it would have.
--     Recomputing the window from now() would silently extend every in-flight key by a whole TTL.
--
-- The id carries over too, which is what makes the anti-join able to say "already moved".
--
-- Two anti-joins rather than one. The first makes a re-run a no-op for rows this statement already
-- moved. The second is the one that matters on a live deployment: between one boot and the next, a
-- request may have claimed the same (scope, key) through the new table, and that claim is newer and
-- authoritative - inserting the old row would violate the unique constraint and fail the whole
-- migration, taking the application's startup with it.
-- ======================================================================================

--changeset ludwig-idempotency:idempotency-0002-migrate-notification-idempotency dbms:postgresql runAlways:true
--comment Moves notification_idempotency into idempotency_claim.
--preconditions onFail:MARK_RAN
--precondition-table-exists table:notification_idempotency
INSERT INTO idempotency_claim (
    id, scope, idempotency_key, request_id, state, fingerprint, lease_expires_at,
    failure_reason, response_status, response_content_type, response_headers, response_body,
    created_at, expires_at)
SELECT
    n.id,
    n.scope,
    n.idempotency_key,
    n.request_id,
    'COMPLETED',
    NULL, NULL, NULL, NULL, NULL, NULL, NULL,
    n.created_at,
    n.expires_at
FROM notification_idempotency n
WHERE NOT EXISTS (SELECT 1 FROM idempotency_claim c WHERE c.id = n.id)
  AND NOT EXISTS (SELECT 1 FROM idempotency_claim c
                  WHERE c.scope = n.scope AND c.idempotency_key = n.idempotency_key);
--rollback DELETE FROM idempotency_claim c USING notification_idempotency n WHERE c.id = n.id
