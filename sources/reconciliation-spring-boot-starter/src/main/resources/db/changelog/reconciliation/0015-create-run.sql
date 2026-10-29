--liquibase formatted sql

-- One row per reconciliation run that acquired its lease: scheduled, manual, dry run or backfill. The id is the run
-- lease's run id, which the audit events and staged inbox rows already carry, so they join to it. The run's status
-- is derived from started_at, finished_at and outcome rather than stored, so the engine never writes a second
-- operation vocabulary.

--changeset ludwig-reconciliation:reconciliation-0015-create-run dbms:postgresql
--comment Run history for the administrative surface, purged by ludwig-reconciliation-run-purge.
CREATE TABLE sync_run (
    id             UUID                     NOT NULL,
    task_name      VARCHAR(255)             NOT NULL,
    tier           VARCHAR(10)              NOT NULL,
    kind           VARCHAR(16)              NOT NULL,
    triggered_by   VARCHAR(255),
    instance       VARCHAR(255),
    correlation_id VARCHAR(128),
    submitted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at     TIMESTAMP WITH TIME ZONE,
    finished_at    TIMESTAMP WITH TIME ZONE,
    outcome        VARCHAR(16),
    failure_code   VARCHAR(128),
    demanded       INT,
    fetched        INT,
    produced       INT,
    result_json    JSONB,
    CONSTRAINT pk_sync_run PRIMARY KEY (id)
);

CREATE INDEX idx_sync_run_task_submitted ON sync_run (task_name, submitted_at DESC, id DESC);

CREATE INDEX idx_sync_run_finished ON sync_run (finished_at) WHERE finished_at IS NOT NULL;
--rollback DROP TABLE sync_run
