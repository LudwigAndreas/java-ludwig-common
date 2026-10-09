--liquibase formatted sql

-- payload is nullable: a NOT FOUND outcome under mark-missing stages a row with no payload.

--changeset ludwig-reconciliation:reconciliation-0001-create-inbox-record dbms:postgresql
--comment The staging inbox: one row per fetched record awaiting or having completed apply.
CREATE TABLE sync_inbox_record (
    id                 UUID                     NOT NULL,
    task_name          VARCHAR(255)             NOT NULL,
    kind               VARCHAR(20)              NOT NULL DEFAULT 'RECORD',
    correlation_key    VARCHAR(512)             NOT NULL,
    payload            JSONB,
    external_version   VARCHAR(255),
    external_timestamp TIMESTAMP WITH TIME ZONE,
    payload_hash       VARCHAR(64),
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status             VARCHAR(20)              NOT NULL DEFAULT 'STAGED',
    attempts           INT                      NOT NULL DEFAULT 0,
    max_attempts       INT                      NOT NULL,
    next_attempt_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_error         TEXT,
    locked_at          TIMESTAMP WITH TIME ZONE,
    locked_by          VARCHAR(255),
    run_id             UUID,
    job_id             UUID,
    correlation_id     VARCHAR(128),
    settled_at         TIMESTAMP WITH TIME ZONE,
    version            BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_inbox_record PRIMARY KEY (id)
);
--rollback DROP TABLE sync_inbox_record
