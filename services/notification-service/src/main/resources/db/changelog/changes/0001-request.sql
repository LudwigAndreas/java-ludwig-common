--liquibase formatted sql

-- What one caller asked for. Deliberately inert: no attempt counter, no lastError, no retry schedule -
-- all of that belongs to notification_delivery, because a request to three recipients where one
-- bounces has no single status.
--
-- Column types follow what db-core's base entities map to, because the application starts with
-- hibernate.ddl-auto=validate: uuid ids, bigint versions, and timestamptz for every instant
-- (Hibernate 6 maps java.time.Instant to TIMESTAMP WITH TIME ZONE).
--
-- priority is an orderable integer weight, not the enum name: the claim query orders by it, and
-- 'NORMAL' sorts after 'HIGH' alphabetically, which would invert the lanes silently.

--changeset ludwig-notification:notification-0001-request dbms:postgresql
--comment What one caller asked for; deliberately carries no retry state.
CREATE TABLE notification_request (
    id                    UUID                     NOT NULL,
    idempotency_key       VARCHAR(255),
    source                VARCHAR(16)              NOT NULL,
    template_key          VARCHAR(128)             NOT NULL,
    category              VARCHAR(128)             NOT NULL,
    category_kind         VARCHAR(16)              NOT NULL,
    priority              INTEGER                  NOT NULL,
    variables             JSONB                    NOT NULL,
    requested_recipients  JSONB                    NOT NULL,
    status                VARCHAR(16)              NOT NULL,
    scheduled_at          TIMESTAMP WITH TIME ZONE,
    tenant_id             VARCHAR(128),
    correlation_id        VARCHAR(128),
    trace_id              VARCHAR(64),
    rejection_reason      VARCHAR(512),
    version               BIGINT                   NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            VARCHAR(255),
    updated_by            VARCHAR(255),
    CONSTRAINT pk_notification_request PRIMARY KEY (id)
);

CREATE INDEX ix_notification_request_created_at ON notification_request (created_at DESC);
--rollback DROP TABLE notification_request
