--liquibase formatted sql

-- One row per enumeration task and scope, enforced rather than assumed. The paging cursor, the
-- incremental watermark and the generation counter are all per scope: a scope discovered today must
-- get a full first walk however far every other scope of the task has advanced, and a single cursor
-- per task cannot say that.

--changeset ludwig-reconciliation:reconciliation-0012-create-scope-state dbms:postgresql
--comment Per-task, per-scope walk state for enumeration tasks.
CREATE TABLE sync_scope_state (
    id                  UUID                     NOT NULL,
    task_name           VARCHAR(255)             NOT NULL,
    scope_key           VARCHAR(512)             NOT NULL,
    generation          BIGINT                   NOT NULL DEFAULT 0,
    walk_kind           VARCHAR(16),
    walk_cursor         TEXT,
    walk_started_at     TIMESTAMP WITH TIME ZONE,
    walk_attempts       INT                      NOT NULL DEFAULT 0,
    watermark           TIMESTAMP WITH TIME ZONE,
    high_water          TIMESTAMP WITH TIME ZONE,
    last_full_at        TIMESTAMP WITH TIME ZONE,
    last_incremental_at TIMESTAMP WITH TIME ZONE,
    next_due_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sweep_held_at       TIMESTAMP WITH TIME ZONE,
    sweep_held_count    INT                      NOT NULL DEFAULT 0,
    last_error          TEXT,
    last_run_id         UUID,
    registered_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_scope_state PRIMARY KEY (id)
);

ALTER TABLE sync_scope_state
    ADD CONSTRAINT ux_sync_scope_state_task_scope UNIQUE (task_name, scope_key);
--rollback DROP TABLE sync_scope_state
