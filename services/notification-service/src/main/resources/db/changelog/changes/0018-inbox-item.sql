--liquibase formatted sql

-- The recipient's in-product inbox: what the platform has told a person, as a list that person owns.
--
-- A separate aggregate from notification_delivery rather than four more columns on it, and the
-- argument is the one this service already makes one level up for why retry state lives on the
-- delivery and not on the request. The two rows are written by different parties and have different
-- lifetimes. A delivery row is a work-queue row written by this service, claimed and updated by the
-- claim query continuously, and deliberately kept narrow. An inbox item is a document mutated by its
-- recipient and kept until they have read it. One table holding both roles would force the retention
-- purge to give a single answer to two different questions, and the delivery is deliberately not an
-- AuditedEntity, so a recipient's own write to it would not be audited the way a user-owned mutation
-- should be.
--
-- delivery_id is NULLABLE with ON DELETE SET NULL, and that is not laxity. The delivery retention
-- window is 90 days from creation while the inbox window is anchored on being read, so the delivery
-- is the row that disappears first and an item outliving its delivery is the NORMAL case. NOT NULL
-- here would make the delivery purge fail, and ON DELETE CASCADE would let that purge take a live
-- unread inbox with it.

--changeset ludwig-notification:notification-0018-inbox-item dbms:postgresql
--comment The recipient-owned inbox item: ownership, classification and the three read-state instants.
CREATE TABLE notification_inbox_item (
    id             UUID                     NOT NULL,
    owner_user_id  VARCHAR(255)             NOT NULL,
    delivery_id    UUID,
    request_id     UUID,
    template_key   VARCHAR(255)             NOT NULL,
    category       VARCHAR(255)             NOT NULL,
    category_class VARCHAR(32)              NOT NULL,
    priority       SMALLINT                 NOT NULL,
    locale         VARCHAR(35)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    seen_at        TIMESTAMP WITH TIME ZONE,
    read_at        TIMESTAMP WITH TIME ZONE,
    dismissed_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_notification_inbox_item PRIMARY KEY (id),
    CONSTRAINT fk_notification_inbox_delivery FOREIGN KEY (delivery_id)
        REFERENCES notification_delivery (id) ON DELETE SET NULL
);

-- The one index the list, the default filter and the unread count all use. Owner first because every
-- query is scoped to exactly one owner and no query may ever span owners; then the two state columns
-- the default list filters on; then the sort key, descending, so the newest-first page is an index
-- scan rather than a sort.
CREATE INDEX ix_notification_inbox_owner
    ON notification_inbox_item (owner_user_id, dismissed_at, read_at, created_at DESC);

-- Retention is anchored on being read or dismissed, never on age, so the purge's predicate is over
-- COALESCE(read_at, dismissed_at) rather than over created_at. A partial index over the settled rows
-- keeps that sweep off the unread ones, which are the rows that must never be purged by age alone.
CREATE INDEX ix_notification_inbox_settled
    ON notification_inbox_item (COALESCE(read_at, dismissed_at))
    WHERE read_at IS NOT NULL OR dismissed_at IS NOT NULL;
--rollback DROP TABLE notification_inbox_item
