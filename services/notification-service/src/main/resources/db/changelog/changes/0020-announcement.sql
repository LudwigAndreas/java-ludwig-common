--liquibase formatted sql

-- A platform announcement: one message for an audience defined by a rule, stored ONCE.
--
-- The audience is two columns - a kind and a value - and not a join table, and that is the whole
-- point of the aggregate. Publishing to a hundred thousand people writes exactly the same rows as
-- publishing to ten; a user created tomorrow is inside an EVERYONE audience without anything having
-- been rewritten; and correcting a typo is one UPDATE rather than a hundred thousand. An ArchUnit
-- rule fails the build if a materialized audience is ever added to the entity as an optimisation,
-- because that reintroduces every one of those problems at once.
--
-- The alternative - one row per recipient, which is what a caller enumerating users and submitting
-- batches produces today - costs 50 000 delivery rows, 50 000 inbox items and 50 000 copies of the
-- same body for one release note, and still misses everybody who joins afterwards.

--changeset ludwig-notification:notification-0020-announcement dbms:postgresql
--comment One announcement, audience as a predicate rather than a materialized list of subjects.
CREATE TABLE notification_announcement (
    id             UUID                     NOT NULL,
    category       VARCHAR(255)             NOT NULL,
    category_class VARCHAR(32)              NOT NULL,
    template_key   VARCHAR(255)             NOT NULL,
    audience_kind  VARCHAR(32)              NOT NULL,
    audience_value VARCHAR(128),
    visible_from   TIMESTAMP WITH TIME ZONE NOT NULL,
    visible_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- AuditedEntity's columns. An announcement is caller-owned - somebody published it, and
    -- "who published this and when" is the first question asked about one - which is the same
    -- reason notification_request extends AuditedEntity and notification_delivery does not.
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    version        BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_announcement PRIMARY KEY (id),
    -- A ROLE audience needs a value and an EVERYONE audience must not have one. Checked in the
    -- database as well as in the service because a null role code would make the visibility
    -- predicate match nobody, silently, for the lifetime of the announcement.
    CONSTRAINT ck_notification_announcement_audience CHECK (
        (audience_kind = 'EVERYONE' AND audience_value IS NULL)
        OR (audience_kind = 'ROLE' AND audience_value IS NOT NULL)),
    CONSTRAINT ck_notification_announcement_window CHECK (visible_until > visible_from)
);

-- The read path's index. visible_until first because every read is bounded by it and most rows in a
-- mature table are expired, so it is the most selective term; visible_from second for the
-- not-yet-started case. The audience is deliberately NOT in this index: it is matched against a
-- short in-memory list of the caller's roles, not scanned.
CREATE INDEX ix_notification_announcement_window
    ON notification_announcement (visible_until, visible_from);
--rollback DROP TABLE notification_announcement
