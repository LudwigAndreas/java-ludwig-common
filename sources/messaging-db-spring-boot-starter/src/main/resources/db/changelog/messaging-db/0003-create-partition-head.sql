--liquibase formatted sql

-- Shared by every service on the database, and each of them may run this changeset against tables
-- another one already created: hence IF NOT EXISTS, and the advisory lock that serializes two services
-- starting at once. No rollback: one service rolling back must not drop the log every other service is
-- using. Dropping it is a deliberate operator step, in the module README.
--changeset ludwig-messaging-db:messaging-db-0003-create-partition-head dbms:postgresql
--comment The next offset of every partition; its row lock serializes writers.
SELECT pg_advisory_xact_lock(hashtext('ludwig-messaging-db'));
CREATE TABLE IF NOT EXISTS ${messaging_db_schema}.partition_head (
    topic         VARCHAR(249) NOT NULL,
    log_partition INT          NOT NULL,
    next_offset   BIGINT       NOT NULL,
    CONSTRAINT pk_partition_head PRIMARY KEY (topic, log_partition)
);
--rollback NOT REQUIRED
