-- The two legacy audit tables, created BEFORE the application starts.
--
-- That ordering is the point of this file rather than an implementation detail. Measured, this module's
-- Liquibase bean runs before the consuming application's own changelog and before the other modules' -
-- so creating these tables from a changelog would create them after the migration had already looked
-- for them, which is the brand-new-database case and has no rows to migrate anyway.
--
-- What a real deployment looks like is this: user_setting_audit and sync_audit_record were created by a
-- PREVIOUS release and are already in the database, with rows in them, before this release's Liquibase
-- runs at all. A container init script is the only fixture that reproduces that, because it runs before
-- Spring.
--
-- The DDL is copied verbatim from usrset-003 and the reconciliation changelog, so the migration is
-- tested against what a deployment actually has rather than against a convenient approximation.

CREATE TABLE user_setting_audit (
    id             UUID PRIMARY KEY,
    tenant_id      VARCHAR(128)             NOT NULL,
    subject        VARCHAR(255)             NOT NULL,
    actor          VARCHAR(255)             NOT NULL,
    action         VARCHAR(32)              NOT NULL,
    setting_key    VARCHAR(128),
    category       VARCHAR(64),
    scope_type     VARCHAR(32),
    scope_id       VARCHAR(255),
    old_value      TEXT,
    new_value      TEXT,
    redacted       BOOLEAN                  NOT NULL DEFAULT FALSE,
    correlation_id VARCHAR(128),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sync_audit_record (
    id             UUID PRIMARY KEY,
    task_name      VARCHAR(255)             NOT NULL,
    category       VARCHAR(20)              NOT NULL,
    event          VARCHAR(64)              NOT NULL,
    subject        VARCHAR(512),
    from_state     VARCHAR(32),
    to_state       VARCHAR(32),
    detail         TEXT,
    principal      VARCHAR(255),
    run_id         UUID,
    correlation_id VARCHAR(128),
    instance       VARCHAR(255),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The rows the migration has to move, including the three cases that make it worth testing: a
-- PII-flagged settings row carrying the old '[redacted]' marker, an ADMIN_READ row whose actor and
-- subject differ, and a reconciliation row with no principal at all.
INSERT INTO user_setting_audit
    (id, tenant_id, subject, actor, action, setting_key, category, scope_type, scope_id,
     old_value, new_value, redacted, correlation_id, occurred_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'acme', 'user-1', 'user-1', 'SET',
     'ui.page-size', 'ui', 'USER', 'user-1', '50', '75', false, 'corr-1', '2020-01-01T00:00:00Z'),
    ('22222222-2222-2222-2222-222222222222', 'acme', 'user-1', 'user-1', 'SET',
     'profile.mobile', 'profile', 'USER', 'user-1', '[redacted]', '[redacted]', true, 'corr-2',
     '2020-01-02T00:00:00Z'),
    ('33333333-3333-3333-3333-333333333333', 'acme', 'user-1', 'admin-1', 'ADMIN_READ',
     NULL, NULL, NULL, NULL, NULL, NULL, false, 'corr-3', '2020-01-03T00:00:00Z');

INSERT INTO sync_audit_record
    (id, task_name, category, event, subject, from_state, to_state, detail, principal,
     run_id, correlation_id, instance, occurred_at)
VALUES
    ('44444444-4444-4444-4444-444444444444', 'partner-sync', 'RECORD', 'record.quarantined',
     'key-9', 'STAGED', 'QUARANTINED', 'checksum mismatch', NULL,
     '55555555-5555-5555-5555-555555555555', 'corr-4', 'pod-a', '2020-02-01T00:00:00Z'),
    ('66666666-6666-6666-6666-666666666666', 'partner-sync', 'OPERATOR', 'operator.retry',
     'key-9', NULL, NULL, 'retried by hand', 'ops-1', NULL, 'corr-5', 'pod-a',
     '2020-02-02T00:00:00Z');
