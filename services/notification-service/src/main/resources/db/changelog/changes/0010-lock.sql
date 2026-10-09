--liquibase formatted sql

-- A leased, cluster-wide mutex for the maintenance jobs. The delivery poller does not use it -
-- SKIP LOCKED already partitions the queue - but the digest collapse and the retention purge are
-- defined over sets of rows rather than over each row independently, and running them on three
-- replicas produces three digests for one recipient.
--
-- A table rather than pg_try_advisory_lock: an advisory lock is held by the session, and with a
-- connection pool the session goes back to the pool when the statement finishes. A row with an
-- explicit lease is inspectable, survives the connection, and fails over on a configured timeout
-- rather than an accidental one.
--
-- Superseded by job-core's job_run_lock; dropped by 0016-drop-lock-table.sql. See 0009 for the history
-- note that covers both.
--
-- The id IS the lock's name, so acquisition is one upsert on the primary key.

--changeset ludwig-notification:notification-0010-lock dbms:postgresql
--comment A leased cluster-wide mutex, since replaced by job-core's job_run_lock.
CREATE TABLE notification_lock (
    id          VARCHAR(255)             NOT NULL,
    owner       VARCHAR(255)             NOT NULL,
    acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_lock PRIMARY KEY (id)
);
--rollback DROP TABLE notification_lock
