--liquibase formatted sql

-- An operator's pause of one consumer group on one topic. Every replica's claim excludes a paused pair, so a pause
-- takes effect cluster-wide at the next claim with no broadcast; a move of the group's position requires one.
-- Shared by every service on the database, hence IF NOT EXISTS under the same advisory lock as the other tables,
-- and no rollback (see 0004).
--changeset ludwig-messaging-db:messaging-db-0005-create-consumer-pause dbms:postgresql
--comment A paused (group, topic) pair, with who paused it, when and why.
SELECT pg_advisory_xact_lock(hashtext('ludwig-messaging-db'));
CREATE TABLE IF NOT EXISTS ${messaging_db_schema}.consumer_pause (
    group_id  VARCHAR(255)             NOT NULL,
    topic     VARCHAR(249)             NOT NULL,
    paused_by VARCHAR(255),
    paused_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    reason    VARCHAR(512),
    CONSTRAINT pk_consumer_pause PRIMARY KEY (group_id, topic)
);
--rollback NOT REQUIRED
