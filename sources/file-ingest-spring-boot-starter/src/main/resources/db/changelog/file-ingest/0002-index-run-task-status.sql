--liquibase formatted sql

-- Covers the two questions asked on every pass: "is a run of this task still in progress" (resume)
-- and "what has this task done lately" (the endpoint and the missing-file check). started_at is in
-- the index because both read it in descending order and a sort of a year of daily runs is otherwise
-- the slowest part of a pass that should do nothing.

--changeset ludwig-file-ingest:file-ingest-0002-index-run-task-status dbms:postgresql
--comment Serves both the resume check and "what has this task done lately".
CREATE INDEX ix_file_ingest_run_task_status
    ON file_ingest_run (task, status, started_at DESC);
--rollback DROP INDEX ix_file_ingest_run_task_status
