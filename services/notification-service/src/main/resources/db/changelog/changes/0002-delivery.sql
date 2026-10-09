--liquibase formatted sql

-- The work queue. One row per recipient per channel, and the only place retry state exists.
--
-- request_id carries ON DELETE CASCADE so the retention purge of a request needs no second statement.
-- The FK takes FOR KEY SHARE on the request row, which does not conflict with the FOR NO KEY UPDATE
-- that the ACCEPTED -> FANNED_OUT transition takes on it - so the hot fan-out path never blocks on the
-- parent.
--
-- recipient_address is nullable: a delivery that never resolved has no destination, and a purged one
-- has had its destination removed. Both are real states.

--changeset ludwig-notification:notification-0002-delivery dbms:postgresql
--comment The work queue: one row per recipient per channel, and the only place retry state lives.
CREATE TABLE notification_delivery (
    id                  UUID                     NOT NULL,
    request_id          UUID                     NOT NULL,
    channel             VARCHAR(16)              NOT NULL,
    recipient_user_id   VARCHAR(255),
    recipient_address   VARCHAR(512),
    variables           JSONB                    NOT NULL,
    recipient_locale    VARCHAR(35)              NOT NULL,
    recipient_timezone  VARCHAR(64)              NOT NULL,
    template_key        VARCHAR(128)             NOT NULL,
    template_version    VARCHAR(64),
    category            VARCHAR(128)             NOT NULL,
    category_kind       VARCHAR(16)              NOT NULL,
    priority            INTEGER                  NOT NULL,
    status              VARCHAR(16)              NOT NULL,
    digest_group        VARCHAR(255),
    collapsed_into_id   UUID,
    attempts            INTEGER                  NOT NULL DEFAULT 0,
    max_attempts        INTEGER                  NOT NULL,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    scheduled_at        TIMESTAMP WITH TIME ZONE,
    claimed_at          TIMESTAMP WITH TIME ZONE,
    claimed_by          VARCHAR(255),
    last_error          TEXT,
    last_failure_kind   VARCHAR(16),
    suppression_reason  VARCHAR(128),
    provider_message_id VARCHAR(255),
    sent_at             TIMESTAMP WITH TIME ZONE,
    delivered_at        TIMESTAMP WITH TIME ZONE,
    settled_at          TIMESTAMP WITH TIME ZONE,
    correlation_id      VARCHAR(128),
    tenant_id           VARCHAR(128),
    dedup_key           VARCHAR(512),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (id),
    CONSTRAINT fk_notification_delivery_request FOREIGN KEY (request_id)
        REFERENCES notification_request (id) ON DELETE CASCADE
);
--rollback DROP TABLE notification_delivery
