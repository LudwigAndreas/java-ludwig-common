--liquibase formatted sql

-- Opt-outs and explicit opt-ins. Absence means allowed: opting out is the exception, so the table
-- stays small and the default is the one that does not silently swallow notifications.
--
-- category '*' means every category the recipient may decline; a NULL channel means every channel.
--
-- Two partial unique indexes rather than one constraint, because channel is nullable and SQL treats
-- NULLs as distinct: a plain UNIQUE (user_id, category, channel) would happily admit five "all
-- channels" rows for one category, and the precedence rule would then be arbitrating between
-- duplicates rather than between genuinely different preferences.

--changeset ludwig-notification:notification-0007-recipient-preference dbms:postgresql
--comment Opt-outs and opt-ins, with two partial unique indexes because channel is nullable.
CREATE TABLE notification_recipient_preference (
    id         UUID                     NOT NULL,
    user_id    VARCHAR(255)             NOT NULL,
    category   VARCHAR(128)             NOT NULL,
    channel    VARCHAR(16),
    allowed    BOOLEAN                  NOT NULL DEFAULT false,
    source     VARCHAR(64)              NOT NULL,
    version    BIGINT                   NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    CONSTRAINT pk_notification_recipient_preference PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_notification_preference_channel
    ON notification_recipient_preference (user_id, category, channel)
    WHERE channel IS NOT NULL;

CREATE UNIQUE INDEX ux_notification_preference_all_channels
    ON notification_recipient_preference (user_id, category)
    WHERE channel IS NULL;

-- The fan-out lookup: four candidate rows per decision, always by subject.
CREATE INDEX ix_notification_preference_user
    ON notification_recipient_preference (user_id, category);
--rollback DROP TABLE notification_recipient_preference
