--liquibase formatted sql

-- One queue entry per (quota, task, instance). Without the constraint, a task that fails to acquire on
-- every tick would add a row per tick and crowd out every other task in the arrival order it is
-- supposed to be waiting in.

--changeset ludwig-reconciliation:reconciliation-0010-create-quota-waiter dbms:postgresql
--comment The fair-arrival queue for a quota that is currently full.
CREATE TABLE sync_quota_waiter (
    id             UUID                     NOT NULL,
    quota_name     VARCHAR(255)             NOT NULL,
    task_name      VARCHAR(255)             NOT NULL,
    owner_instance VARCHAR(255)             NOT NULL,
    enqueued_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    heartbeat_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version        BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_quota_waiter PRIMARY KEY (id)
);

ALTER TABLE sync_quota_waiter
    ADD CONSTRAINT ux_sync_quota_waiter_identity UNIQUE (quota_name, task_name, owner_instance);

CREATE INDEX idx_sync_quota_waiter_queue ON sync_quota_waiter (quota_name, enqueued_at);
--rollback DROP TABLE sync_quota_waiter
