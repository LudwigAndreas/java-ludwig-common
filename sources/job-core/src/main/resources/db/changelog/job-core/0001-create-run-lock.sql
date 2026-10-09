--liquibase formatted sql

-- Schema for job-core. The changeset id and author are namespaced ("job-core-NNNN",
-- "ludwig-job-core") so they never collide with a consuming application's own changesets in the
-- shared DATABASECHANGELOG table - see JobCoreLiquibaseAutoConfiguration, which registers this
-- changelog as an independent SpringLiquibase bean.

--changeset ludwig-job-core:job-core-0001-create-run-lock dbms:postgresql
--comment The job_run_lock table: one row per named lease.
--
-- The lock name is the real identity of the row; the surrogate id exists only because the
-- SKIP LOCKED claim helper addresses rows by `id`. The unique constraint is what makes the
-- "create it if it is not there" insert an ON CONFLICT no-op rather than a second row that
-- would let two instances each hold "the" lease.
--
-- `owner` is null while the lease is free, and is never used alone to decide whether it is
-- held: expires_at is. expires_at is the single source of truth for "is this lease held" - a
-- holder that stops renewing stops being the holder, with no participation from the holder
-- required, which is the only way a lease survives the process holding it being killed.
CREATE TABLE job_run_lock (
    id           UUID                     NOT NULL,
    lock_name    VARCHAR(255)             NOT NULL,
    owner        VARCHAR(255),
    run_id       UUID,
    acquired_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_job_run_lock PRIMARY KEY (id),
    CONSTRAINT ux_job_run_lock_name UNIQUE (lock_name)
);
--rollback DROP TABLE job_run_lock
