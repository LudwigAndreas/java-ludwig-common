--liquibase formatted sql

-- Addresses now come from the identity projection and locale/timezone/quiet hours from the settings
-- replica, so nothing in this table has an owner here any more.
--
-- One capability genuinely goes: a per-user webhook URL. A webhook is a machine destination rather than
-- a person's contact point, and a request that wants one names it literally as an ADDRESS recipient -
-- which it could always do.

--changeset ludwig-notification:notification-0015-drop-recipient-profile dbms:postgresql
--comment Drops the contact table, now replaced by the identity projection and settings replica.
DROP TABLE notification_recipient_profile;
--rollback CREATE TABLE notification_recipient_profile (
--rollback     id                UUID                     NOT NULL,
--rollback     user_id           VARCHAR(255)             NOT NULL,
--rollback     email_address     VARCHAR(320),
--rollback     chat_address      VARCHAR(255),
--rollback     webhook_url       VARCHAR(1024),
--rollback     locale            VARCHAR(35),
--rollback     timezone          VARCHAR(64),
--rollback     quiet_hours_start TIME,
--rollback     quiet_hours_end   TIME,
--rollback     tenant_id         VARCHAR(128),
--rollback     version           BIGINT                   NOT NULL DEFAULT 0,
--rollback     created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     created_by        VARCHAR(255),
--rollback     updated_by        VARCHAR(255),
--rollback     CONSTRAINT pk_notification_recipient_profile PRIMARY KEY (id)
--rollback );
