--liquibase formatted sql

--changeset ludwig-outbox:outbox-0005-create-status-history dbms:postgresql
--comment One row per status transition of a message, for answering "what happened to this event".
CREATE TABLE outbox_status_history (
    id                UUID                     NOT NULL,
    outbox_message_id UUID                     NOT NULL,
    status            VARCHAR(20)              NOT NULL,
    attempt_number    INT                      NOT NULL,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    detail            TEXT,
    CONSTRAINT pk_outbox_status_history PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_status_history_message_id ON outbox_status_history (outbox_message_id);
--rollback DROP TABLE outbox_status_history
