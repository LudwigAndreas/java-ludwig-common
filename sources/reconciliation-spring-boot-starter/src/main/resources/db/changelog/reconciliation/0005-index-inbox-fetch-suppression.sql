--liquibase formatted sql

-- The fetch pass's "which keys should I skip this run" lookup: keys with a failure that is not due
-- yet, and keys whose failure budget is spent. Both are read once per run and are tiny compared to the
-- table, so the index is partial on exactly those rows.

--changeset ludwig-reconciliation:reconciliation-0005-index-inbox-fetch-suppression dbms:postgresql
--comment The fetch pass's "which keys should I skip this run" lookup.
CREATE INDEX idx_sync_inbox_fetch_failure
    ON sync_inbox_record (task_name, correlation_key, next_attempt_at)
    WHERE kind = 'FETCH_FAILURE' AND status IN ('FAILED', 'QUARANTINED');
--rollback DROP INDEX idx_sync_inbox_fetch_failure
