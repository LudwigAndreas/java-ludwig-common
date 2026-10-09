--liquibase formatted sql

-- Who changed what, when, from what to what, and at which layer - plus the correlation id that joins
-- the row to the logs and traces of the same request. Values of PII-flagged settings are stored
-- redacted; `redacted` says so, so a reader is never guessing what a marker means.
--
-- Superseded by audit-core's single audit_event trail: audit-spring-boot-starter's 0002 migrates these
-- rows and rewrites the old '[redacted]' marker to the platform's one mask.

--changeset ludwig-user-settings:user-settings-0003-create-user-setting-audit dbms:postgresql
--comment The module's own setting-change trail, since consolidated into audit_event.
CREATE TABLE user_setting_audit (
    id             UUID                     NOT NULL,
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
    redacted       BOOLEAN                  NOT NULL DEFAULT false,
    correlation_id VARCHAR(128),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_setting_audit PRIMARY KEY (id)
);

CREATE INDEX idx_user_setting_audit_subject
    ON user_setting_audit (tenant_id, subject, occurred_at);

-- The retention purge scans by age alone, across every tenant.
CREATE INDEX idx_user_setting_audit_occurred
    ON user_setting_audit (occurred_at);
--rollback DROP TABLE user_setting_audit
