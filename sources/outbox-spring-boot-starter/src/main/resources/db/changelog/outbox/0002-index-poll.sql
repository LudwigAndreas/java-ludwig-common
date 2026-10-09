--liquibase formatted sql

--changeset ludwig-outbox:outbox-0002-index-poll dbms:postgresql
--comment Partial index serving the poller: only rows it can actually claim.
CREATE INDEX idx_outbox_message_poll ON outbox_message (status, next_attempt_at) WHERE status IN ('PENDING', 'FAILED');
--rollback DROP INDEX idx_outbox_message_poll
