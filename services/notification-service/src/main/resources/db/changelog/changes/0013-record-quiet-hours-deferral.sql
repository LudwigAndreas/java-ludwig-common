--liquibase formatted sql

-- Preferences move out of this service.
--
-- This service used to own two tables that were never really its own:
-- notification_recipient_profile (address, locale, timezone, quiet hours) and
-- notification_recipient_preference (per-category, per-channel opt-outs). Both described the USER, not
-- the delivery, and keeping them here meant two competing stores for the same facts the moment any
-- other service needed them.
--
-- They are replaced by two replicas this service keeps but does not own:
--   - contact data comes from the identity projection, populated because this deployment sets
--     ludwig.identity.contact.enabled - the OIDC provider verifies addresses and is the single source
--     of truth for them;
--   - preferences come from user-settings-spring-boot-starter in projection mode, fed by the account
--     service's change stream, so no call leaves this process on the dispatch path.
--
-- The suppression list is untouched and stays here. A hard bounce is delivery state derived from
-- provider feedback only this service receives; the user never chose it.
--
-- This changeset records the fan-out's preference decision, snapshotted on the delivery. A retry three
-- hours later must not silently behave differently because a preference changed in between, and "why
-- did this arrive at seven in the morning" has to be answerable from the row rather than from a
-- time-travel query against settings history. The locale, timezone and address were already
-- snapshotted; this is the decision they fed.

--changeset ludwig-notification:notification-0013-record-quiet-hours-deferral dbms:postgresql
--comment Snapshots the fan-out's quiet-hours decision onto the delivery row.
ALTER TABLE notification_delivery
    ADD COLUMN quiet_hours_deferred BOOLEAN NOT NULL DEFAULT false;
--rollback ALTER TABLE notification_delivery DROP COLUMN quiet_hours_deferred
