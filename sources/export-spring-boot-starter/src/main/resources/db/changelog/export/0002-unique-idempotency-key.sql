--liquibase formatted sql

-- The idempotency key is what makes "a retry restarts the run from scratch" safe: the same request
-- cannot be in flight twice, so restarting one is not a race with another copy of it. A unique
-- constraint rather than a check in application code, because the check would have to be a read
-- followed by a write and two instances would pass it at the same time.

--changeset ludwig-export:export-0002-unique-idempotency-key dbms:postgresql
--comment The constraint that makes restarting a run safe rather than a race.
ALTER TABLE export_report_run
    ADD CONSTRAINT uq_export_report_run_idempotency_key UNIQUE (idempotency_key);
--rollback ALTER TABLE export_report_run DROP CONSTRAINT uq_export_report_run_idempotency_key
