--liquibase formatted sql

-- The append-only status trail, mirroring outbox_status_history. Deliberately carries no foreign key
-- to notification_delivery: an FK would take a FOR KEY SHARE lock on the delivery row on every history
-- insert, and that row is the hottest in the service. The retention purge deletes history by age
-- rather than relying on a cascade.

--changeset ludwig-notification:notification-0004-delivery-status-history dbms:postgresql
--comment The append-only status trail, deliberately without a foreign key to the hot delivery row.
CREATE TABLE notification_delivery_status_history (
    id             UUID                     NOT NULL,
    delivery_id    UUID                     NOT NULL,
    from_status    VARCHAR(16),
    to_status      VARCHAR(16)              NOT NULL,
    attempt_number INTEGER                  NOT NULL DEFAULT 0,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    detail         TEXT,
    CONSTRAINT pk_notification_delivery_status_history PRIMARY KEY (id)
);

CREATE INDEX ix_notification_history_delivery
    ON notification_delivery_status_history (delivery_id, occurred_at);
-- The purge selects on age alone; without this it would scan the largest table here.
CREATE INDEX ix_notification_history_occurred_at
    ON notification_delivery_status_history (occurred_at);
--rollback DROP TABLE notification_delivery_status_history
