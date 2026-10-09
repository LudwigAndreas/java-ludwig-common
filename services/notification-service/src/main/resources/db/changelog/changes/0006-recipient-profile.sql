--liquibase formatted sql

-- Where to reach one user, in what language, and when not to.
--
-- This table exists because the identity projection cannot supply it and should not: security_user
-- stores a subject, a display name, a tenant, a status and a role set, and its own documentation gives
-- the reason - it is directory data replicated into every service that uses the module, so every extra
-- field is another copy of personal data. An address, a chat handle and a home timezone are not
-- authorization inputs and have no business being copied estate-wide. The notification service needs
-- them anyway, because preferences and quiet hours hang off them, so it owns the contact record.

--changeset ludwig-notification:notification-0006-recipient-profile dbms:postgresql
--comment Where to reach one user, in what language, and when not to.
CREATE TABLE notification_recipient_profile (
    id                UUID                     NOT NULL,
    user_id           VARCHAR(255)             NOT NULL,
    email_address     VARCHAR(320),
    chat_address      VARCHAR(255),
    webhook_url       VARCHAR(1024),
    locale            VARCHAR(35),
    timezone          VARCHAR(64),
    quiet_hours_start TIME,
    quiet_hours_end   TIME,
    tenant_id         VARCHAR(128),
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by        VARCHAR(255),
    updated_by        VARCHAR(255),
    CONSTRAINT pk_notification_recipient_profile PRIMARY KEY (id),
    CONSTRAINT uk_notification_profile_user UNIQUE (user_id)
);
--rollback DROP TABLE notification_recipient_profile
