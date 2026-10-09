--liquibase formatted sql

--changeset ludwig-export:export-0008-index-output-run dbms:postgresql
--comment "The files for this run, by format" - the lookup the result link resolves through.
CREATE INDEX idx_export_report_output_run
    ON export_report_output (run_id, format_id);
--rollback DROP INDEX idx_export_report_output_run
