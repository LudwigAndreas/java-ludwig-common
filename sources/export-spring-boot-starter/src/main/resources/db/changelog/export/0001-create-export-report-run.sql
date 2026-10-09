--liquibase formatted sql

--changeset ludwig-export:export-0001-create-export-report-run dbms:postgresql
--comment One row per requested report run, carrying its lease, progress and failure detail.
CREATE TABLE export_report_run (
    id                  UUID                     NOT NULL,
    definition_key      VARCHAR(128)             NOT NULL,
    definition_version  INT                      NOT NULL DEFAULT 1,
    saved_report_id     UUID,
    requester           VARCHAR(255)             NOT NULL,
    principal_snapshot  JSONB,
    parameters          JSONB,
    filter_expression   TEXT,
    column_ids          JSONB,
    formats             JSONB                    NOT NULL,
    locale              VARCHAR(35)              NOT NULL,
    time_zone           VARCHAR(64)              NOT NULL,
    status              VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    attempts            INT                      NOT NULL DEFAULT 0,
    max_attempts        INT                      NOT NULL,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    cancel_requested    BOOLEAN                  NOT NULL DEFAULT false,
    claimed_by          VARCHAR(255),
    claimed_at          TIMESTAMP WITH TIME ZONE,
    heartbeat_at        TIMESTAMP WITH TIME ZONE,
    lease_until         TIMESTAMP WITH TIME ZONE,
    rows_written        BIGINT                   NOT NULL DEFAULT 0,
    percent             INT                      NOT NULL DEFAULT 0,
    started_at          TIMESTAMP WITH TIME ZONE,
    finished_at         TIMESTAMP WITH TIME ZONE,
    failure_code        VARCHAR(128),
    failure_message_key VARCHAR(128),
    failure_arguments   JSONB,
    degraded_stages     JSONB,
    omitted_sheets      JSONB,
    correlation_id      VARCHAR(64),
    idempotency_key     VARCHAR(128)             NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by          VARCHAR(255),
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_export_report_run PRIMARY KEY (id)
);
--rollback DROP TABLE export_report_run
