--liquibase formatted sql

-- The apply pass's claim predicate, exactly. Partial on the claimable statuses so the index stays
-- proportional to the backlog rather than to the history: on a task that has applied ten million
-- records and has forty waiting, a full index would be four orders of magnitude larger than the thing
-- being searched.

--changeset ludwig-reconciliation:reconciliation-0002-index-inbox-claim dbms:postgresql
--comment The apply pass's claim index, partial on the claimable statuses.
CREATE INDEX idx_sync_inbox_claim
    ON sync_inbox_record (task_name, kind, next_attempt_at, received_at, id)
    WHERE status IN ('STAGED', 'FAILED', 'DEFERRED');
--rollback DROP INDEX idx_sync_inbox_claim
