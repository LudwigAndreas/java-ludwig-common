--liquibase formatted sql

-- The walk pass's "which scopes are due" lookup, and the sweep's "which members did the last walks
-- not see" lookup. The unique constraints already cover lookups by key; these two cover the range
-- scans. The third is partial on the rows an operator and the clean-walk check actually ask about.

--changeset ludwig-reconciliation:reconciliation-0014-index-scope-state-due dbms:postgresql
--comment Due-scope selection, sweep-candidate selection and the not-active member lookup.
CREATE INDEX idx_sync_scope_state_due
    ON sync_scope_state (task_name, next_due_at);

CREATE INDEX idx_sync_scope_member_generation
    ON sync_scope_member (task_name, scope_key, seen_generation);

CREATE INDEX idx_sync_scope_member_not_active
    ON sync_scope_member (task_name, scope_key, status)
    WHERE status <> 'ACTIVE';
--rollback DROP INDEX idx_sync_scope_member_not_active; DROP INDEX idx_sync_scope_member_generation; DROP INDEX idx_sync_scope_state_due
