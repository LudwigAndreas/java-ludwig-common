--liquibase formatted sql

-- The module's own legacy audit table. Nothing in the repository ever wrote it, and
-- audit-spring-boot-starter's 0003 migrates whatever a deployment may have written into the
-- platform's single audit_event trail. It is created here so that migration has a table to read.

--changeset ludwig-reconciliation:reconciliation-0011-create-audit-record dbms:postgresql
--comment The module's legacy audit table, superseded by audit-core's single trail.
CREATE TABLE sync_audit_record (
    id             UUID                     NOT NULL,
    task_name      VARCHAR(255)             NOT NULL,
    category       VARCHAR(20)              NOT NULL,
    event          VARCHAR(64)              NOT NULL,
    subject        VARCHAR(512),
    from_state     VARCHAR(32),
    to_state       VARCHAR(32),
    detail         TEXT,
    principal      VARCHAR(255),
    run_id         UUID,
    correlation_id VARCHAR(128),
    instance       VARCHAR(255),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_sync_audit_record PRIMARY KEY (id)
);

CREATE INDEX idx_sync_audit_task_time ON sync_audit_record (task_name, occurred_at);
CREATE INDEX idx_sync_audit_subject ON sync_audit_record (subject);
--rollback DROP TABLE sync_audit_record
