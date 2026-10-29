--liquibase formatted sql

-- The party the actor acted through (RFC 8693 act.sub), e.g. an administrative UI: the channel, distinct from
-- on_behalf_of. Nullable, with no backfill: a row written before this column existed did not record its channel.
--
-- A partial index, because most events are made directly and "everything done through the UI" is the question.

--changeset ludwig-audit:audit-0006-actor-via dbms:postgresql
--comment Records the party each audit event's actor acted through, for searching by acting party.
ALTER TABLE audit_event ADD COLUMN actor_via VARCHAR(255);
CREATE INDEX idx_audit_event_actor_via ON audit_event (actor_via, occurred_at) WHERE actor_via IS NOT NULL;
--rollback DROP INDEX idx_audit_event_actor_via;
--rollback ALTER TABLE audit_event DROP COLUMN actor_via;
