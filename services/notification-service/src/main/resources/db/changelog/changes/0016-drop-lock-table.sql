--liquibase formatted sql

-- Drops notification_lock. The service's leased mutex is now job-core's RunLock, backed by
-- job_run_lock, which the master changelog includes immediately before this file.
--
-- Where did the data go: nowhere, and there was none to move. Every row in this table was an ephemeral
-- lease - a claim on the next few minutes of one maintenance job - and the whole design of a lease is
-- that it means nothing once it expires. A row copied into job_run_lock would either already have
-- lapsed or would block the new lock for the remainder of a lease taken by a process that no longer
-- exists. The correct migration of a lease table is to drop it and let the next tick acquire afresh.
--
-- The two lock names this service takes (notification-digest, notification-retention) are unchanged, so
-- the first run after this deployment simply creates its row in job_run_lock.
--
-- Ordering: this must run after the job-core changelog's include, or a fresh database would briefly
-- have neither lock table. See db.changelog-master.xml.

--changeset ludwig-notification:notification-0016-drop-lock-table dbms:postgresql
--comment Drops notification_lock, replaced by job-core's job_run_lock.
DROP TABLE notification_lock;
--rollback CREATE TABLE notification_lock (
--rollback     id          VARCHAR(255)             NOT NULL,
--rollback     owner       VARCHAR(255)             NOT NULL,
--rollback     acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     CONSTRAINT pk_notification_lock PRIMARY KEY (id)
--rollback );
