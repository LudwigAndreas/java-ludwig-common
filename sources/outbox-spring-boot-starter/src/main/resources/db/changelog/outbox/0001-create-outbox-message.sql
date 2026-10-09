--liquibase formatted sql

--changeset ludwig-outbox:outbox-0001-create-outbox-message dbms:postgresql
--comment The outbox table: one row per message awaiting publication.
CREATE TABLE outbox_message (
    id              UUID                     NOT NULL,
    aggregate_type  VARCHAR(255)             NOT NULL,
    aggregate_id    VARCHAR(255)             NOT NULL,
    event_type      VARCHAR(255)             NOT NULL,
    event_version   INT                      NOT NULL DEFAULT 1,
    payload         JSONB                    NOT NULL,
    headers         JSONB,
    ordering_key    VARCHAR(255),
    transport       VARCHAR(50)              NOT NULL,
    destination     VARCHAR(255)             NOT NULL,
    idempotency_key VARCHAR(255),
    status          VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    attempts        INT                      NOT NULL DEFAULT 0,
    max_attempts    INT                      NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    locked_at       TIMESTAMP WITH TIME ZONE,
    locked_by       VARCHAR(255),
    last_error      TEXT,
    published_at    TIMESTAMP WITH TIME ZONE,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_message PRIMARY KEY (id)
);
--rollback DROP TABLE outbox_message
