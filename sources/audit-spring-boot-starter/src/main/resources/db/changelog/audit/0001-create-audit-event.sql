--liquibase formatted sql

-- The platform's single audit trail. One flat table for every category, rather than one shaped table
-- per subsystem: the question this table exists to answer is "what happened around this, in order,
-- on this day", and answering it across nine tables means nine queries and a manual merge at the
-- moment somebody is trying to work out what a person did.
--
-- Append-only. There is no update path in the module and no delete path outside the retention purge,
-- and AuditEventEntity extends db-core's SnapshotEntity so SnapshotImmutabilityListener rejects any
-- @PreUpdate rather than this being a convention.
--
-- No foreign keys to anything, ever. An FK would force a key-share lock on the parent row on every
-- insert, contending with the work being audited - and the trail has to outlive the rows it
-- describes, which is exactly the case somebody asks about later.
--
-- Column notes:
--
--   id                 AuditEvent.id, assigned when the event is constructed rather than by the
--                      database. That is what makes it usable as the idempotency key when the event
--                      is shipped off the platform: a redelivery collides on the key instead of
--                      appending a second copy of the same event.
--   imported_at        When the row was written, against occurred_at for when the thing happened.
--   occurred_at        The two differ for an event relayed from another service, and only occurred_at
--                      has meaning to an auditor.
--   source_system      Which deployment wrote the row. From db-core's ExternalEntity.
--   actor_subject      The actor's stable id, and the same string db-core stamps into created_by for
--                      the same actor - see ActorResolverAuditorProvider. Not nullable: an event with
--                      no attributable actor carries 'system', because a null here would be
--                      indistinguishable from a bug in whatever resolved it.
--   resource_id        Null for an event about a set rather than an object: a query-level
--                      authorization decision whose answer was a predicate, a whole-profile read.
--   attributes         Everything module-specific, already redacted before it reached this module.
--                      jsonb and not a child table of name/value pairs: the attributes differ per
--                      action and are read as a whole rather than filtered on, so a child table would
--                      turn every read into a join and every write into N inserts on the path of the
--                      work being audited. Deliberately not GIN-indexed - that costs write throughput
--                      on every audited operation to speed up a query an auditor runs monthly, and a
--                      deployment that needs it can add one.

--changeset ludwig-audit:audit-0001-create-audit-event dbms:postgresql
--comment The platform's single append-only audit trail.
CREATE TABLE audit_event (
    id                 UUID                     NOT NULL,
    imported_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    source_system      VARCHAR(64)              NOT NULL,
    source_version     VARCHAR(64),
    source_timestamp   TIMESTAMP WITH TIME ZONE,
    occurred_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    category           VARCHAR(64)              NOT NULL,
    action             VARCHAR(128)             NOT NULL,
    actor_subject      VARCHAR(255)             NOT NULL,
    actor_type         VARCHAR(64),
    actor_display_name VARCHAR(255),
    on_behalf_of       VARCHAR(255),
    resource_type      VARCHAR(128),
    resource_id        VARCHAR(255),
    resource_name      VARCHAR(512),
    outcome            VARCHAR(16)              NOT NULL,
    reason             TEXT,
    correlation_id     VARCHAR(128),
    trace_id           VARCHAR(64),
    attributes         JSONB,
    CONSTRAINT pk_audit_event PRIMARY KEY (id)
);

-- "Everything this person did, newest first" - the query the consolidation exists for.
CREATE INDEX idx_audit_event_actor ON audit_event (actor_subject, occurred_at);
CREATE INDEX idx_audit_event_category ON audit_event (category, occurred_at);
-- The retention purge scans by age alone when no category overrides it.
CREATE INDEX idx_audit_event_occurred ON audit_event (occurred_at);
CREATE INDEX idx_audit_event_resource ON audit_event (resource_type, resource_id);
-- "What else happened in that request", which is the question a correlation id exists for.
CREATE INDEX idx_audit_event_correlation ON audit_event (correlation_id);
--rollback DROP TABLE audit_event
