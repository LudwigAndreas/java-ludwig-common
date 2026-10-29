--liquibase formatted sql

--changeset ludwig-job-core:job-core-0004-create-control dbms:postgresql
--comment The replica-control channel: a command per broadcast, and one acknowledgement per targeted replica.
--
-- targets is frozen at submission - the replicas live then - so a replica that starts later never runs an
-- older command, and one that dies stays named as unacknowledged. Each replica polls for commands that
-- target it, are within their deadline and have no acknowledgement of its own. result is a control's
-- optional per-replica answer, at most 4 KiB.
CREATE TABLE job_control_command (
    id           UUID                     NOT NULL,
    control      VARCHAR(100)             NOT NULL,
    arguments    JSONB                    NOT NULL,
    operation    VARCHAR(100)             NOT NULL,
    submitted_by VARCHAR(255)             NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deadline     TIMESTAMP WITH TIME ZONE NOT NULL,
    targets      TEXT[]                   NOT NULL,
    CONSTRAINT pk_job_control_command PRIMARY KEY (id)
);
CREATE INDEX ix_job_control_command_deadline ON job_control_command (deadline);
CREATE INDEX ix_job_control_command_submitted ON job_control_command (submitted_at);

CREATE TABLE job_control_ack (
    command_id      UUID                     NOT NULL,
    owner           VARCHAR(255)             NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    outcome         VARCHAR(20)              NOT NULL,
    failure_code    VARCHAR(255),
    result          JSONB,
    CONSTRAINT pk_job_control_ack PRIMARY KEY (command_id, owner),
    CONSTRAINT fk_job_control_ack_command FOREIGN KEY (command_id) REFERENCES job_control_command (id)
        ON DELETE CASCADE
);
--rollback DROP TABLE job_control_ack; DROP TABLE job_control_command
