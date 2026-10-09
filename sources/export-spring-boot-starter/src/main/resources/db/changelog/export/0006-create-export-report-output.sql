--liquibase formatted sql

--changeset ludwig-export:export-0006-create-export-report-output dbms:postgresql
--comment One row per produced file, with the digest and the expiry the purge reads.
CREATE TABLE export_report_output (
    id         UUID                     NOT NULL,
    run_id     UUID                     NOT NULL,
    format_id  VARCHAR(32)              NOT NULL,
    sink_uri   TEXT                     NOT NULL,
    file_name  VARCHAR(255)             NOT NULL,
    media_type VARCHAR(128)             NOT NULL,
    size_bytes BIGINT                   NOT NULL,
    sha256     VARCHAR(64)              NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    purged_at  TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version    BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_export_report_output PRIMARY KEY (id)
);
--rollback DROP TABLE export_report_output
