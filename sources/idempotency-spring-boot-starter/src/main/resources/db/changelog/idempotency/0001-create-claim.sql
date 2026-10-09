--liquibase formatted sql

-- The work-dedup claim table, promoted out of notification-service. Without it a service
-- double-sends the moment it runs at more than one replica: a Kafka consumer is at-least-once by
-- construction, and REST callers retry on timeouts that were often successful writes.
--
-- Column notes, which are the design rather than decoration:
--
--   scope             Scoped, not global: a Kafka record key and an HTTP Idempotency-Key come from
--                     different namespaces, and a collision between them would silently drop a
--                     genuine request. The HTTP surface narrows it further, to one scope per
--                     endpoint, because a client that generates one key per user action and calls
--                     two endpoints with it is not sending a duplicate.
--   request_id        The holder. Updatable, unlike the scope and the key: a reclaim rewrites it in
--                     the same statement that would otherwise have inserted the row.
--   state             IN_PROGRESS | COMPLETED | FAILED. A string rather than an ordinal: an ordinal
--                     makes inserting a constant a data migration, and makes this table unreadable
--                     to whoever is looking at it during an incident without the Java source.
--   fingerprint       A hash of the request, not the request. Cheaper, and it keeps this table out
--                     of the set of places that accumulate request payloads - which would otherwise
--                     need audit-core's redaction rules and an entitlement check on every reader.
--   lease_expires_at  Null on every state but IN_PROGRESS. The single source of truth for "is this
--                     holder still working", with no participation from the holder required - which
--                     is the only way a claim survives the process holding it being killed.
--   response_headers  text and not jsonb, unlike every other JSON column in this platform: nothing
--                     ever queries inside this value. Written once by the filter, read once by the
--                     replay, whole.
--   response_body     bytea so a replay is byte-for-byte. Re-encoding a body through a varchar
--                     changes the length of anything outside ASCII, and a replay a client can tell
--                     from the original is not one.
--   expires_at        The window in which a retry is recognised, which makes it a correctness
--                     parameter rather than housekeeping: shortening it converts duplicates into
--                     double executions, not into disk savings.

--changeset ludwig-idempotency:idempotency-0001-create-claim dbms:postgresql
--comment The work-dedup claim table, promoted out of notification-service.
CREATE TABLE idempotency_claim (
    id                    UUID                     NOT NULL,
    scope                 VARCHAR(64)              NOT NULL,
    idempotency_key       VARCHAR(255)             NOT NULL,
    request_id            UUID                     NOT NULL,
    state                 VARCHAR(16)              NOT NULL,
    fingerprint           VARCHAR(64),
    lease_expires_at      TIMESTAMP WITH TIME ZONE,
    failure_reason        VARCHAR(512),
    response_status       INTEGER,
    response_content_type VARCHAR(128),
    response_headers      TEXT,
    response_body         BYTEA,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_claim PRIMARY KEY (id)
);

-- THE constraint. Everything else about this module is convenience; this is what makes it correct.
-- Two replicas processing the same at-least-once record both run the same
-- INSERT ... ON CONFLICT DO UPDATE, the second blocks on the first's row lock, and when it proceeds
-- it reads the committed winner instead of inserting a second claim. It is also the conflict target
-- the statement names, so a rename here is a compile-clean, silently-broken deployment.
ALTER TABLE idempotency_claim
    ADD CONSTRAINT uk_idempotency_claim_key UNIQUE (scope, idempotency_key);

-- Serves the purge, which is the only query that reads rows by window rather than by key.
CREATE INDEX ix_idempotency_claim_expiry ON idempotency_claim (expires_at);
--rollback DROP TABLE idempotency_claim
