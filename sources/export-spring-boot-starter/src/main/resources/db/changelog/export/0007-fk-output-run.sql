--liquibase formatted sql

-- ON DELETE CASCADE: an output has no meaning without its run, and the retention policy deletes
-- runs. The alternative - orphaned output rows pointing at files whose run is gone - is a set of
-- sink uris nothing knows how to attribute or expire.

--changeset ludwig-export:export-0007-fk-output-run dbms:postgresql
--comment An output has no meaning without its run, so it cascades with it.
ALTER TABLE export_report_output
    ADD CONSTRAINT fk_export_report_output_run FOREIGN KEY (run_id)
        REFERENCES export_report_run (id) ON DELETE CASCADE;
--rollback ALTER TABLE export_report_output DROP CONSTRAINT fk_export_report_output_run
