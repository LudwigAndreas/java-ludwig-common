--liquibase formatted sql

--changeset ludwig-outbox:outbox-0006-finished-at dbms:postgresql
--comment When a message became terminal, which is what retention is measured from. PUBLISHED rows take their
--comment published_at; DEAD_LETTER and DISCARDED rows have no timestamp of their own, so they take the migration
--comment time and get a full retention period before anything may delete them.
ALTER TABLE outbox_message ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;

UPDATE outbox_message SET finished_at = published_at WHERE status = 'PUBLISHED';
UPDATE outbox_message SET finished_at = now() WHERE status IN ('DEAD_LETTER', 'DISCARDED');

CREATE INDEX idx_outbox_message_finished ON outbox_message (status, finished_at) WHERE finished_at IS NOT NULL;
--rollback DROP INDEX idx_outbox_message_finished;
--rollback ALTER TABLE outbox_message DROP COLUMN finished_at;
