--liquibase formatted sql

-- The email fan-out of one announcement, as a run this service owns.
--
-- THIS SERVICE'S OWN TABLE, deliberately. The platform's long-running-operation contract is explicit
-- that there is no shared operation table and that web-core carries no persistence dependency: each
-- module keeps its own run table and maps onto the OperationResponse envelope at its edge. This is
-- that table for this operation.
--
-- The status column holds the platform's own vocabulary as a string. No enum of this service's own
-- restates those values - RuleGroup.OPERATIONS fails the build on a restatement, and this change
-- relies on that rule rather than adding a copy of it.
--
-- No audit columns, deliberately: "who did this and when" is recorded against the ANNOUNCEMENT, and
-- a created_by here would be whichever replica inserted the row. The run is machinery that followed
-- from somebody's publish, not something anybody authored.
--
-- cursor_subject is what makes a run resumable: a keyset position, not an offset. A run interrupted
-- by a lost lease, a restart or a cancellation continues from the last subject it committed rather
-- than starting again. Exactly-once across that resume is NOT this table's job - it rests on
-- notification_delivery's existing unique dedup key, so a batch that re-processes a recipient cannot
-- create a second delivery for them.

--changeset ludwig-notification:notification-0023-announcement-email-run dbms:postgresql
--comment This service's own run table for an announcement's email fan-out; status is the platform vocabulary.
CREATE TABLE notification_announcement_email_run (
    id                UUID                     NOT NULL,
    announcement_id   UUID                     NOT NULL,
    status            VARCHAR(32)              NOT NULL,
    -- The last subject id this run committed a batch for. Null before the first batch.
    cursor_subject    VARCHAR(255),
    deliveries_created BIGINT                  NOT NULL DEFAULT 0,
    -- Null until the audience has been counted, which is deliberate: the operation envelope's
    -- progress has a nullable total for exactly this case, and guessing one would be worse than
    -- admitting it is not yet known.
    audience_total    BIGINT,
    cancel_requested  BOOLEAN                  NOT NULL DEFAULT FALSE,
    last_error        VARCHAR(2000),
    submitted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE,
    finished_at       TIMESTAMP WITH TIME ZONE,
    -- Set explicitly by the service when a batch commits, so it means "last made progress" rather
    -- than "last ORM write" - the same reason notification_delivery keeps explicit instants instead
    -- of inheriting an audited updated_at.
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_announcement_email_run PRIMARY KEY (id),
    CONSTRAINT fk_notification_announcement_email_run FOREIGN KEY (announcement_id)
        REFERENCES notification_announcement (id) ON DELETE CASCADE,
    -- One run per announcement. A second would email the audience twice, and the unique dedup key on
    -- the delivery would hide it by silently dropping the duplicates - leaving a run that reports
    -- having created thousands of deliveries that do not exist.
    CONSTRAINT uq_notification_announcement_email_run UNIQUE (announcement_id)
);

-- The scheduler claims unfinished runs, so it filters on status. Partial, because a mature table is
-- almost entirely finished runs and those are never claimed again.
CREATE INDEX ix_notification_announcement_email_run_claimable
    ON notification_announcement_email_run (status)
    WHERE finished_at IS NULL;
--rollback DROP TABLE notification_announcement_email_run
