--liquibase formatted sql

-- Opt-outs now live in the account service and reach this one as projected settings.
--
-- Dropped rather than left behind: two stores for one fact is the thing this change exists to remove,
-- and a table nothing writes to is a table somebody eventually reads. Operators migrating an existing
-- deployment export these rows into the account service's settings before running this changeset - see
-- the README.
--
-- The rollback recreates the structure only. The rows are gone; they exist in the account service now,
-- and recreating an empty table is the honest rollback rather than a silent data promise.

--changeset ludwig-notification:notification-0014-drop-recipient-preference dbms:postgresql
--comment Drops the opt-out table, now projected from the account service.
DROP TABLE notification_recipient_preference;
--rollback CREATE TABLE notification_recipient_preference (
--rollback     id         UUID                     NOT NULL,
--rollback     user_id    VARCHAR(255)             NOT NULL,
--rollback     category   VARCHAR(128)             NOT NULL,
--rollback     channel    VARCHAR(16),
--rollback     allowed    BOOLEAN                  NOT NULL,
--rollback     source     VARCHAR(64)              NOT NULL,
--rollback     version    BIGINT                   NOT NULL DEFAULT 0,
--rollback     created_at TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
--rollback     created_by VARCHAR(255),
--rollback     updated_by VARCHAR(255),
--rollback     CONSTRAINT pk_notification_recipient_preference PRIMARY KEY (id)
--rollback );
