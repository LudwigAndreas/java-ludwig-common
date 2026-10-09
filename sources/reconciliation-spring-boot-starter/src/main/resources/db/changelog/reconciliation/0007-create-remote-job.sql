--liquibase formatted sql

-- The idempotency key is committed before the submit call and is how an ambiguous submit is matched
-- against the partner's listing of active jobs. Two rows sharing one would make that match ambiguous
-- in exactly the situation it exists to disambiguate.

--changeset ludwig-reconciliation:reconciliation-0007-create-remote-job dbms:postgresql
--comment One row per asynchronous job submitted to a partner.
CREATE TABLE sync_remote_job (
    id              UUID                     NOT NULL,
    task_name       VARCHAR(255)             NOT NULL,
    idempotency_key VARCHAR(255)             NOT NULL,
    external_handle VARCHAR(512),
    state           VARCHAR(20)              NOT NULL DEFAULT 'PENDING_SUBMIT',
    demand_keys     JSONB,
    submitted_at    TIMESTAMP WITH TIME ZONE,
    last_polled_at  TIMESTAMP WITH TIME ZONE,
    poll_attempts   INT                      NOT NULL DEFAULT 0,
    next_poll_at    TIMESTAMP WITH TIME ZONE,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    collect_cursor  TEXT,
    quota_lease_id  UUID,
    owner_instance  VARCHAR(255),
    last_error      TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    settled_at      TIMESTAMP WITH TIME ZONE,
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_remote_job PRIMARY KEY (id)
);

ALTER TABLE sync_remote_job
    ADD CONSTRAINT ux_sync_remote_job_idempotency_key UNIQUE (idempotency_key);
--rollback DROP TABLE sync_remote_job
