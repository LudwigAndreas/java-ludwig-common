--liquibase formatted sql

-- Destinations this service will not write to, whatever any preference says. A suppression is a fact
-- about the address rather than a wish of its owner, which is why even a transactional notification
-- honours it.
--
-- address is normalized (trimmed, lower-cased) by the service before it is written, so the lookup is
-- an equality match on the unique constraint rather than a function scan. A NULL expires_at is
-- permanent: a spam complaint does not stop being true.

--changeset ludwig-notification:notification-0008-suppression dbms:postgresql
--comment Destinations this service will not write to, whatever any preference says.
CREATE TABLE notification_suppression (
    id         UUID                     NOT NULL,
    channel    VARCHAR(16)              NOT NULL,
    address    VARCHAR(512)             NOT NULL,
    reason     VARCHAR(64)              NOT NULL,
    detail     TEXT,
    expires_at TIMESTAMP WITH TIME ZONE,
    version    BIGINT                   NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    CONSTRAINT pk_notification_suppression PRIMARY KEY (id),
    CONSTRAINT uk_notification_suppression_address UNIQUE (channel, address)
);

-- The compaction job selects on expiry alone.
CREATE INDEX ix_notification_suppression_expiry ON notification_suppression (expires_at);
--rollback DROP TABLE notification_suppression
