--liquibase formatted sql

-- One recipient's dismissal of one announcement.
--
-- Written on first dismissal and NEVER in advance, which is what keeps the aggregate's row count
-- independent of audience size: an announcement nobody dismissed has no rows here at all, and one
-- that half the estate dismissed has half as many as a per-recipient fan-out would have created
-- before anybody had even read it.
--
-- ON DELETE CASCADE, which is the OPPOSITE answer to notification_inbox_item.delivery_id's SET NULL,
-- and the reason is the direction of the lifetime. There, the delivery is purged first by design and
-- the item has to survive it, so CASCADE would let the delivery purge delete a live unread inbox.
-- Here the marker is meaningless without the announcement it refers to - it records "I dismissed
-- that", and once that is gone there is nothing to have dismissed - so the marker must go with it.
--
-- There is deliberately no seen_at or read_at. The inbox has three instants because an item is a
-- document somebody works through; an announcement is either in your way or it is not. A column with
-- no consumer is one somebody later writes a query against.

--changeset ludwig-notification:notification-0022-announcement-marker dbms:postgresql
--comment Lazy per-recipient dismissal; no row exists until somebody dismisses.
CREATE TABLE notification_announcement_marker (
    announcement_id UUID                     NOT NULL,
    owner_user_id   VARCHAR(255)             NOT NULL,
    dismissed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_announcement_marker PRIMARY KEY (announcement_id, owner_user_id),
    CONSTRAINT fk_notification_announcement_marker FOREIGN KEY (announcement_id)
        REFERENCES notification_announcement (id) ON DELETE CASCADE
);

-- The read path asks "has THIS owner dismissed any of these announcements", so the owner leads.
-- The primary key is (announcement_id, owner_user_id) because that is the uniqueness constraint;
-- this index is the one the NOT EXISTS actually uses.
CREATE INDEX ix_notification_announcement_marker_owner
    ON notification_announcement_marker (owner_user_id, announcement_id);
--rollback DROP TABLE notification_announcement_marker
