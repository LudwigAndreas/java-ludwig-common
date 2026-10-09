--liquibase formatted sql

--changeset ludwig-outbox:outbox-0003-index-ordering-key dbms:postgresql
--comment Partial index: most messages carry no ordering key, and those rows need no entry.
CREATE INDEX idx_outbox_message_ordering_key ON outbox_message (ordering_key) WHERE ordering_key IS NOT NULL;
--rollback DROP INDEX idx_outbox_message_ordering_key
