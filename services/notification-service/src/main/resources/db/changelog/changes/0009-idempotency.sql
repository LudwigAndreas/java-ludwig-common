--liquibase formatted sql

-- HISTORY. This changeset and 0010 created capabilities that have since been promoted out of this
-- service. They are kept because a changeset that has run is a fact rather than a document.
--
-- That principle is why the unify-sql-migrations change is the only thing that has ever rewritten
-- them: every changeset in this repository was re-authored from XML into formatted SQL at once, on the
-- recorded basis that no deployed database had applied any of them. Ids and authors were renumbered
-- in the same pass. If that assumption was ever wrong for a deployment, its recovery is
-- `liquibase clearChecksums` plus a DATABASECHANGELOG id update - see the CHANGELOG entry. Do not take
-- this file as licence to edit an applied changeset.
--
-- What happened to each:
--   - this changeset created notification_idempotency, now idempotency_claim in
--     idempotency-spring-boot-starter. The rows are moved by that module's own changeset and the table
--     is dropped by 0017-drop-idempotency.sql, which runs after it;
--   - 0010 created notification_lock, now job_run_lock in job-core, dropped by 0016-drop-lock-table.sql;
--   - 0011 and 0012 are still this service's own. The rate limiter is cluster-wide and could be
--     promoted; the template revision table is about FreeMarker sources this service owns and is not a
--     platform concern.
--
-- Consumer-side idempotency. Without it this service double-sends the moment it runs at more than one
-- replica: the Kafka consumer is at-least-once by construction, and REST callers retry on timeouts
-- that were often successful writes.
--
-- scope is the ingress namespace: a Kafka record key and an HTTP Idempotency-Key come from different
-- namespaces, and a collision between them would silently drop a genuine request.

--changeset ludwig-notification:notification-0009-idempotency dbms:postgresql
--comment Consumer-side idempotency, since promoted to idempotency-spring-boot-starter.
CREATE TABLE notification_idempotency (
    id              UUID                     NOT NULL,
    scope           VARCHAR(64)              NOT NULL,
    idempotency_key VARCHAR(255)             NOT NULL,
    request_id      UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_idempotency PRIMARY KEY (id)
);

-- THE constraint. Everything else about idempotency here is convenience; this is what makes it
-- correct. Two replicas processing the same at-least-once record both run the same
-- INSERT ... ON CONFLICT DO UPDATE, the second blocks on the first's row lock, and when it proceeds it
-- reads the committed winner instead of inserting a second claim.
ALTER TABLE notification_idempotency
    ADD CONSTRAINT uk_notification_idempotency_key UNIQUE (scope, idempotency_key);

CREATE INDEX ix_notification_idempotency_expiry ON notification_idempotency (expires_at);
--rollback DROP TABLE notification_idempotency
