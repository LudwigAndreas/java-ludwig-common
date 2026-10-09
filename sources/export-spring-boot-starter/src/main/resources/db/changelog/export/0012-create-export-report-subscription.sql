--liquibase formatted sql

--changeset ludwig-export:export-0012-create-export-report-subscription dbms:postgresql
--comment A cron schedule that produces a saved report and delivers it to recipients.
CREATE TABLE export_report_subscription (
    id              UUID                     NOT NULL,
    saved_report_id UUID                     NOT NULL,
    cron            VARCHAR(128)             NOT NULL,
    time_zone       VARCHAR(64)              NOT NULL DEFAULT 'UTC',
    recipients      JSONB,
    format_id       VARCHAR(32)              NOT NULL,
    run_as          VARCHAR(255)             NOT NULL,
    enabled         BOOLEAN                  NOT NULL DEFAULT true,
    last_run_at     TIMESTAMP WITH TIME ZONE,
    last_run_id     UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_export_report_subscription PRIMARY KEY (id)
);
--rollback DROP TABLE export_report_subscription
