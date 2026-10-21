--liquibase formatted sql

-- Shared by every service on the database, and each of them may run this changeset against tables
-- another one already created: hence IF NOT EXISTS, and the advisory lock that serializes two services
-- starting at once. No rollback: one service rolling back must not drop the log every other service is
-- using. Dropping it is a deliberate operator step, in the module README.
--changeset ludwig-messaging-db:messaging-db-0002-create-message-log dbms:postgresql
--comment One row per record: the log itself.
SELECT pg_advisory_xact_lock(hashtext('ludwig-messaging-db'));
CREATE TABLE IF NOT EXISTS ${messaging_db_schema}.message_log (
    topic         VARCHAR(249)             NOT NULL,
    log_partition INT                      NOT NULL,
    log_offset    BIGINT                   NOT NULL,
    record_key    BYTEA,
    headers       JSONB                    NOT NULL DEFAULT '[]',
    payload       BYTEA,
    produced_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_log PRIMARY KEY (topic, log_partition, log_offset)
);
CREATE INDEX IF NOT EXISTS ix_message_log_produced_at ON ${messaging_db_schema}.message_log (produced_at);
--rollback NOT REQUIRED
