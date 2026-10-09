--liquibase formatted sql

--changeset ludwig-export:export-0010-create-export-saved-report dbms:postgresql
--comment A named, reusable set of parameters and columns over one report definition.
CREATE TABLE export_saved_report (
    id                UUID                     NOT NULL,
    definition_key    VARCHAR(128)             NOT NULL,
    name              VARCHAR(255)             NOT NULL,
    description       TEXT,
    parameters        JSONB,
    column_ids        JSONB,
    filter_expression TEXT,
    format_id         VARCHAR(32),
    revision          INT                      NOT NULL DEFAULT 1,
    enabled           BOOLEAN                  NOT NULL DEFAULT true,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by        VARCHAR(255),
    updated_at        TIMESTAMP WITH TIME ZONE,
    updated_by        VARCHAR(255),
    version           BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_export_saved_report PRIMARY KEY (id)
);
--rollback DROP TABLE export_saved_report
