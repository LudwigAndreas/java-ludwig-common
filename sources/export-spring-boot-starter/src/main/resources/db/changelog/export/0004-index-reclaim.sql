--liquibase formatted sql

-- The reclaim index, for the other half of the lease: rows a dead instance left in RUNNING with an
-- expired lease. Separate from the claim index because the predicate is a different status and a
-- different column, and one index serving both would be usable for neither.

--changeset ludwig-export:export-0004-index-reclaim dbms:postgresql
--comment The reclaim index: RUNNING rows whose lease has expired.
CREATE INDEX idx_export_report_run_reclaim
    ON export_report_run (lease_until)
    WHERE status = 'RUNNING';
--rollback DROP INDEX IF EXISTS idx_export_report_run_reclaim
