--liquibase formatted sql

--changeset ludwig-export:export-0005-index-requester dbms:postgresql
--comment Serves the per-user quota check and "show me my reports", which are one query shape.
CREATE INDEX idx_export_report_run_requester
    ON export_report_run (requester, created_at DESC);
--rollback DROP INDEX idx_export_report_run_requester
