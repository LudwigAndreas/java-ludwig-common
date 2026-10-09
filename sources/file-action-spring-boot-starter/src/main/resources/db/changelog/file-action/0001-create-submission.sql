--liquibase formatted sql

-- One user-submitted file and what became of it. The row is created before anything parses the file,
-- so that a submission which fails during the read is still a record of what was uploaded and by
-- whom - which is the question asked three days later when somebody disputes the result.
--
-- Column notes:
--
--   action                 The configured action this submission belongs to. Part of every lookup,
--                          because the id alone would let a caller poll one action's submission
--                          through another's endpoint.
--   object_uri             The stored object, as a full s3:// or file:// URI rather than a bucket and
--                          a key: ObjectUri is the one place that form is parsed, and splitting it
--                          here would be a second place.
--   content_sha256         The content hash, which is also the idempotency key. varchar, not
--                          char(64), even though a SHA-256 hex digest is always exactly 64
--                          characters: Postgres implements char(n) as bpchar, which pads and which
--                          Hibernate's schema validation reports as a type mismatch against the
--                          entity's String - so a consuming service with ddl-auto=validate refuses to
--                          start. crud-service-example found that; this module's own tests did not,
--                          because they do not validate the schema against the entities.
--   declared_*             What the client said it was sending. Kept for the message and the audit
--                          record, and deliberately never used to choose a parser - see FormatSniffer.
--   source_format          What the content actually turned out to be.
--   submitted_by           Who uploaded it. A subject string rather than a foreign key: identity is
--                          owned by identity-provider-service and a foreign key here would be a copy
--                          of it that goes stale.
--   expires_at             When a VALIDATED submission stops being confirmable, and when a terminal
--                          one's stored bytes become collectable. One column for both because they are
--                          the same question - "after this moment, this row's artifacts are gone" -
--                          and two columns would need a rule about which wins.
--   locale, zone           The caller's locale and zone at submission, so that a reject report produced
--                          later by a worker with no request renders in the language the person who
--                          uploaded it reads.
--   rows_*                 Counts, which are what the envelope's progress and the caller's summary are
--                          built from.
--   failure_code           Why a REJECTED submission was refused: a message code from
--                          FileActionProblemCodes, resolved against the bundle when it is rendered.
--                          Never a formatted message - a row read back in a different locale from the
--                          one that wrote it must still render correctly.
--   bound_rows_uri         The bound-row artifact a CONFIRM-mode submission applies, and the reject
--   error_report_uri       report. Both are object URIs; both are null until there is something there.
--   locked_by              The lease a DEFERRED submission is claimed under. Exactly the shape
--   lease_expires_at       job-core's SkipLockedClaim expects: a holder and an expiry, so that a pod
--                          dying mid-apply releases the submission to another instance rather than
--                          wedging it until somebody notices.
--   cancellation_requested Cooperative cancellation: a flag the running apply checks, not an interrupt.
--                          A cancel endpoint answers 202 because of this column - the stop is
--                          requested, not achieved.

--changeset ludwig-file-action:file-action-0001-create-submission dbms:postgresql
--comment One user-submitted file and what became of it.
CREATE TABLE file_action_submission (
    id                     UUID                     NOT NULL,
    action                 VARCHAR(128)             NOT NULL,
    state                  VARCHAR(32)              NOT NULL,
    object_uri             VARCHAR(1024)            NOT NULL,
    content_sha256         VARCHAR(64)              NOT NULL,
    size_bytes             BIGINT                   NOT NULL,
    declared_filename      VARCHAR(512),
    declared_content_type  VARCHAR(255),
    source_format          VARCHAR(16),
    sheet                  VARCHAR(255),
    submitted_by           VARCHAR(255),
    submitted_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE,
    finished_at            TIMESTAMP WITH TIME ZONE,
    expires_at             TIMESTAMP WITH TIME ZONE,
    locale                 VARCHAR(35),
    zone                   VARCHAR(64),
    correlation_id         VARCHAR(128),
    rows_read              BIGINT                   NOT NULL DEFAULT 0,
    rows_applied           BIGINT                   NOT NULL DEFAULT 0,
    rows_rejected          BIGINT                   NOT NULL DEFAULT 0,
    rows_skipped           BIGINT                   NOT NULL DEFAULT 0,
    failure_code           VARCHAR(128),
    failure_args           TEXT,
    bound_rows_uri         VARCHAR(1024),
    error_report_uri       VARCHAR(1024),
    locked_by              VARCHAR(255),
    lease_expires_at       TIMESTAMP WITH TIME ZONE,
    attempts               INTEGER                  NOT NULL DEFAULT 0,
    cancellation_requested BOOLEAN                  NOT NULL DEFAULT false,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE,
    version                BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_file_action_submission PRIMARY KEY (id)
);

-- The dedup constraint. Per action and per submitter, not global: two people legitimately uploading
-- the same price list are two submissions, and the same person uploading the same file to two
-- different actions is two submissions as well. Making it global would reject both.
CREATE UNIQUE INDEX ux_file_action_submission_content
    ON file_action_submission (action, content_sha256, submitted_by);

-- The claim query's index: the deferred worker looks for submissions of a given state whose lease has
-- expired, oldest first. Without this it is a sequential scan on every tick.
CREATE INDEX ix_file_action_submission_claim
    ON file_action_submission (state, lease_expires_at, submitted_at);

-- The retention job's index.
CREATE INDEX ix_file_action_submission_expiry
    ON file_action_submission (expires_at);
--rollback DROP TABLE file_action_submission
