--liquibase formatted sql

-- Serves both guard rails on the apply path: "what is the newest state already applied for this key"
-- (stale-write protection) and "was this exact payload already applied" (idempotency). Restricted to
-- settled-successfully rows, which is the only history either question is about.

--changeset ludwig-reconciliation:reconciliation-0003-index-inbox-key-history dbms:postgresql
--comment Serves stale-write protection and payload idempotency on the apply path.
CREATE INDEX idx_sync_inbox_applied_history
    ON sync_inbox_record (task_name, correlation_key, settled_at DESC)
    WHERE status IN ('APPLIED', 'UNCHANGED');
--rollback DROP INDEX idx_sync_inbox_applied_history
