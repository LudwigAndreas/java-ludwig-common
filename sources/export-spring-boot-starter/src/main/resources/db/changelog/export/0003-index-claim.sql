--liquibase formatted sql

-- THE CLAIM INDEX. This is the index the poller's FOR UPDATE SKIP LOCKED statement runs on, and its
-- column order is its due predicate and ORDER BY, in that order:
--
--     WHERE status = 'PENDING' AND next_attempt_at <= now()
--     ORDER BY next_attempt_at, id
--
-- Partial on PENDING because that is a vanishing fraction of the table on any busy estate - a full
-- index would be mostly succeeded runs nobody will ever claim, and would keep growing. The trailing
-- id is what makes the claim order total; without it two rows sharing a timestamp claim in an order
-- the planner chooses, which is the difference between a starved row and a fair queue.

--changeset ludwig-export:export-0003-index-claim dbms:postgresql
--comment The poller's claim index: partial on PENDING, ordered as the claim statement reads it.
CREATE INDEX idx_export_report_run_claim
    ON export_report_run (next_attempt_at, id)
    WHERE status = 'PENDING';
--rollback DROP INDEX IF EXISTS idx_export_report_run_claim
