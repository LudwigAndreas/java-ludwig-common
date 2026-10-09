--liquibase formatted sql

--changeset ludwig-reconciliation:reconciliation-0004-index-inbox-stale-and-ops dbms:postgresql
--comment Stale-claim recovery, the quarantine listing, and requeue by remote job.
-- Stale-claim recovery: rows left PROCESSING by an instance that died.
CREATE INDEX idx_sync_inbox_stale
    ON sync_inbox_record (locked_at)
    WHERE status = 'PROCESSING';

-- The actuator's quarantine listing and requeue, and the quarantined-backlog gauge.
CREATE INDEX idx_sync_inbox_quarantined
    ON sync_inbox_record (task_name, settled_at)
    WHERE status = 'QUARANTINED';

-- Requeueing the demand an expired or failed remote job covered.
CREATE INDEX idx_sync_inbox_job
    ON sync_inbox_record (job_id)
    WHERE job_id IS NOT NULL;
--rollback DROP INDEX idx_sync_inbox_stale;
--rollback DROP INDEX idx_sync_inbox_quarantined;
--rollback DROP INDEX idx_sync_inbox_job;
