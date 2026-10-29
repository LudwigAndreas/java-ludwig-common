--liquibase formatted sql

--changeset ludwig-job-core:job-core-0003-create-instance dbms:postgresql
--comment The job_instance table: one heartbeat row per running replica, the registry replica controls target.
--
-- owner is the replica's JobInstanceIdentity. Liveness is judged against the database's now(), never a
-- replica's clock: a row seen within three poll intervals is live. Rows unseen for a day are deleted by the
-- poller that writes them.
CREATE TABLE job_instance (
    owner        VARCHAR(255)             NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_job_instance PRIMARY KEY (owner)
);
CREATE INDEX ix_job_instance_last_seen ON job_instance (last_seen_at);
--rollback DROP TABLE job_instance
