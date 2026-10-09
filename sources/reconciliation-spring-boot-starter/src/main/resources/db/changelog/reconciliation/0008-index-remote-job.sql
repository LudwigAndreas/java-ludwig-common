--liquibase formatted sql

--changeset ludwig-reconciliation:reconciliation-0008-index-remote-job dbms:postgresql
--comment The poll claim, the collect claim, and the adoption/expiry listing.
-- The poll scheduler's claim: in-flight jobs whose next probe is due.
CREATE INDEX idx_sync_remote_job_poll
    ON sync_remote_job (task_name, next_poll_at, id)
    WHERE state IN ('SUBMITTED', 'RUNNING');

-- The collect scheduler's claim, and the resume-after-restart path.
CREATE INDEX idx_sync_remote_job_collect
    ON sync_remote_job (task_name, id)
    WHERE state IN ('SUCCEEDED', 'COLLECTING');

-- Adoption on startup, expiry sweeping, and the in-flight listing in the actuator.
CREATE INDEX idx_sync_remote_job_active
    ON sync_remote_job (task_name, expires_at)
    WHERE state IN ('PENDING_SUBMIT', 'SUBMITTED', 'RUNNING', 'SUCCEEDED', 'COLLECTING', 'ORPHANED');
--rollback DROP INDEX idx_sync_remote_job_poll;
--rollback DROP INDEX idx_sync_remote_job_collect;
--rollback DROP INDEX idx_sync_remote_job_active;
