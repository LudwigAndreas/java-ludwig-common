--liquibase formatted sql

-- One narrow row per scope and member. It is what lets the engine say which members a completed full
-- walk did not see without knowing the integration's schema, and its payload hash is what lets an
-- unchanged item be skipped without calling the integration at all. The table is proportional to
-- the mirrored data, never to history: a member that leaves a scope loses its row.

--changeset ludwig-reconciliation:reconciliation-0013-create-scope-member dbms:postgresql
--comment Scope membership for enumeration tasks, with the generation that last saw each member.
CREATE TABLE sync_scope_member (
    id              UUID                     NOT NULL,
    task_name       VARCHAR(255)             NOT NULL,
    scope_key       VARCHAR(512)             NOT NULL,
    member_key      VARCHAR(512)             NOT NULL,
    seen_generation BIGINT                   NOT NULL DEFAULT 0,
    payload_hash    VARCHAR(128),
    status          VARCHAR(16)              NOT NULL DEFAULT 'ACTIVE',
    attempts        INT                      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error      TEXT,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_sync_scope_member PRIMARY KEY (id)
);

ALTER TABLE sync_scope_member
    ADD CONSTRAINT ux_sync_scope_member_task_scope_member UNIQUE (task_name, scope_key, member_key);
--rollback DROP TABLE sync_scope_member
