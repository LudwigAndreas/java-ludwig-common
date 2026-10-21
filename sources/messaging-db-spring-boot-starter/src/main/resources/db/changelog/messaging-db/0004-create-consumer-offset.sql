--liquibase formatted sql

-- Shared by every service on the database, and each of them may run this changeset against tables
-- another one already created: hence IF NOT EXISTS, and the advisory lock that serializes two services
-- starting at once. No rollback: one service rolling back must not drop the log every other service is
-- using. Dropping it is a deliberate operator step, in the module README.
--changeset ludwig-messaging-db:messaging-db-0004-create-consumer-offset dbms:postgresql
--comment Each consumer group's position per partition; its row lock is the assignment.
SELECT pg_advisory_xact_lock(hashtext('ludwig-messaging-db'));
CREATE TABLE IF NOT EXISTS ${messaging_db_schema}.consumer_offset (
    group_id         VARCHAR(255)             NOT NULL,
    topic            VARCHAR(249)             NOT NULL,
    log_partition    INT                      NOT NULL,
    committed_offset BIGINT                   NOT NULL,
    attempts         INT                      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_error       VARCHAR(255),
    CONSTRAINT pk_consumer_offset PRIMARY KEY (group_id, topic, log_partition)
);
CREATE INDEX IF NOT EXISTS ix_consumer_offset_claim
    ON ${messaging_db_schema}.consumer_offset (group_id, next_attempt_at);
--rollback NOT REQUIRED
