--liquibase formatted sql

-- An announcer's withdrawal of a published announcement, recorded on the announcement itself.
--
-- A marker rather than moving visible_until to now: ck_notification_announcement_window requires
-- visible_until > visible_from, so a not-yet-open announcement could not be ended that way, and the
-- original window is part of the record of what was announced.
--
-- All three nullable: an announcement that was never withdrawn has none of them. The reason is
-- optional because a withdrawal must not be blocked on prose.

--changeset ludwig-notification:notification-0024-announcement-withdrawal dbms:postgresql
--comment Withdrawal marker on the announcement; hidden from recipients, purged by retention.
ALTER TABLE notification_announcement
    ADD COLUMN withdrawn_at      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN withdrawn_by      VARCHAR(255),
    ADD COLUMN withdrawal_reason VARCHAR(500);

-- Retention's second branch, withdrawn_at < cutoff. Partial, because almost every row is never
-- withdrawn and indexing their nulls would only make the index larger.
CREATE INDEX ix_notification_announcement_withdrawn
    ON notification_announcement (withdrawn_at)
    WHERE withdrawn_at IS NOT NULL;
--rollback DROP INDEX ix_notification_announcement_withdrawn;
--rollback ALTER TABLE notification_announcement DROP COLUMN withdrawal_reason, DROP COLUMN withdrawn_by, DROP COLUMN withdrawn_at;
