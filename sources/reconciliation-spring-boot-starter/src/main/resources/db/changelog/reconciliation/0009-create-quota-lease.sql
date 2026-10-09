--liquibase formatted sql

-- Counting live slots is the hottest question asked of this table - once per acquisition attempt per
-- task per tick - and it is always "for this quota, how many have not expired".

--changeset ludwig-reconciliation:reconciliation-0009-create-quota-lease dbms:postgresql
--comment One row per held quota slot, with a heartbeat and a hard lifetime ceiling.
CREATE TABLE sync_quota_lease (
    id              UUID                     NOT NULL,
    quota_name      VARCHAR(255)             NOT NULL,
    task_name       VARCHAR(255)             NOT NULL,
    owner_instance  VARCHAR(255)             NOT NULL,
    job_id          UUID,
    acquired_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    heartbeat_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    max_lifetime_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_quota_lease PRIMARY KEY (id)
);

CREATE INDEX idx_sync_quota_lease_live ON sync_quota_lease (quota_name, expires_at);
CREATE INDEX idx_sync_quota_lease_job ON sync_quota_lease (job_id);
--rollback DROP TABLE sync_quota_lease
