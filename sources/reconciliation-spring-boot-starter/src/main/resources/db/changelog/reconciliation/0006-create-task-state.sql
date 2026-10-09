--liquibase formatted sql

-- One row per task and tier, enforced rather than assumed: a second row for the same pair would give
-- two cursors for one sweep, and whichever one a given instance happened to read would decide where
-- that instance resumed.

--changeset ludwig-reconciliation:reconciliation-0006-create-task-state dbms:postgresql
--comment Per-task, per-tier cursor and watermark.
CREATE TABLE sync_task_state (
    id               UUID                     NOT NULL,
    task_name        VARCHAR(255)             NOT NULL,
    tier             VARCHAR(10)              NOT NULL DEFAULT 'HOT',
    cursor           TEXT,
    watermark        TIMESTAMP WITH TIME ZONE,
    sweep_started_at TIMESTAMP WITH TIME ZONE,
    last_run_at      TIMESTAMP WITH TIME ZONE,
    last_run_id      UUID,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_task_state PRIMARY KEY (id)
);

ALTER TABLE sync_task_state
    ADD CONSTRAINT ux_sync_task_state_task_tier UNIQUE (task_name, tier);
--rollback DROP TABLE sync_task_state
