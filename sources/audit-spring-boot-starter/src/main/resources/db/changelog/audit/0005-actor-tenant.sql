--liquibase formatted sql

-- The tenant the actor belonged to, so that trail search can confine a tenant's administrator to their own
-- tenant's events. Nullable, with no backfill: a row written before this column existed has no recorded actor
-- tenant, and inventing one from today's directory would attribute an old action to whatever tenant the person is
-- in now. Such rows are visible to service principals only, which the trail search states.
--
-- Indexed with occurred_at, because a confined search is always "this tenant's events, newest first".

--changeset ludwig-audit:audit-0005-actor-tenant dbms:postgresql
--comment Records the actor's tenant on each audit event, for tenant-confined trail search.
ALTER TABLE audit_event ADD COLUMN actor_tenant VARCHAR(255);
CREATE INDEX idx_audit_event_actor_tenant ON audit_event (actor_tenant, occurred_at);
--rollback DROP INDEX idx_audit_event_actor_tenant;
--rollback ALTER TABLE audit_event DROP COLUMN actor_tenant;
