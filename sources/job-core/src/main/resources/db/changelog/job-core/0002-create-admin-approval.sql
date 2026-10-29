--liquibase formatted sql

--changeset ludwig-job-core:job-core-0002-create-admin-approval dbms:postgresql
--comment The job_admin_approval table: privileged administrative requests held for a second person's approval.
--
-- One row per approval. request_body holds the stored request only while the approval can still run; it is
-- nulled when the approval reaches a terminal state, because a privileged body can carry what the audit
-- envelope would redact. outcome_body keeps at most 16 KiB of how the executed request was answered.
-- Every state change is a conditional UPDATE ... WHERE state = ?, so two approvers racing produce one
-- execution. Listed by operation and state, newest first.
CREATE TABLE job_admin_approval (
    id                   UUID                     NOT NULL,
    operation            VARCHAR(100)             NOT NULL,
    request_method       VARCHAR(10)              NOT NULL,
    request_path         VARCHAR(2048)            NOT NULL,
    request_query        VARCHAR(4096),
    request_content_type VARCHAR(255),
    request_language     VARCHAR(255),
    request_reason       VARCHAR(500),
    request_body         BYTEA,
    requested_by         VARCHAR(255)             NOT NULL,
    requester_type       VARCHAR(20)              NOT NULL,
    requested_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    state                VARCHAR(20)              NOT NULL,
    decided_by           VARCHAR(255),
    decided_at           TIMESTAMP WITH TIME ZONE,
    outcome_status       INTEGER,
    outcome_content_type VARCHAR(255),
    outcome_body         BYTEA,
    CONSTRAINT pk_job_admin_approval PRIMARY KEY (id)
);
CREATE INDEX ix_job_admin_approval_operation ON job_admin_approval (operation, state, requested_at);
--rollback DROP TABLE job_admin_approval
