--liquibase formatted sql

--changeset ludwig-outbox:outbox-0004-unique-idempotency-key dbms:postgresql
--comment Partial unique index, so that many rows may have no idempotency key but no two share one.
CREATE UNIQUE INDEX ux_outbox_message_idempotency_key ON outbox_message (idempotency_key) WHERE idempotency_key IS NOT NULL;
--rollback DROP INDEX ux_outbox_message_idempotency_key
