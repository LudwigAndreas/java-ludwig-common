--liquibase formatted sql

-- One stored value at one scope. The natural key is (tenant, scope type, scope id, setting key); the
-- surrogate id exists only because the platform's entity base classes assume one.
--
-- Column notes:
--
--   tenant_id   Never nullable. A nullable tenant would mean "every tenant", and every scoped query
--               would then have to be written as `tenant = ? OR tenant IS NULL` - one forgotten
--               clause away from a cross-tenant read. The deployment-wide layer is configuration.
--   value_text  Opaque. Nothing joins, filters or reports on this column, which is the condition that
--               makes storing a typed value as text acceptable instead of an EAV schema. There is
--               deliberately no index on it.
--   removed     A tombstone: the value was reset, and this row records when. Deleting the row would
--               be tidier and is not order-tolerant - a projection told "removed at 12:05" before
--               "set at 12:00" would have nothing to compare the late event against.
--   changed_at  When the change happened at the OWNER, which in a projection is not when this row
--               was written. Comparing against this is what lets a replayed or out-of-order event be
--               dropped instead of resurrecting a value the user has already changed.

--changeset ludwig-user-settings:user-settings-0001-create-user-setting-value dbms:postgresql
--comment One stored setting value at one scope, with a tombstone rather than a delete.
CREATE TABLE user_setting_value (
    id         UUID                     NOT NULL,
    version    BIGINT                   NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    tenant_id  VARCHAR(128)             NOT NULL,
    scope_type VARCHAR(32)              NOT NULL,
    scope_id   VARCHAR(255)             NOT NULL,
    setting_key VARCHAR(128)            NOT NULL,
    value_text TEXT,
    value_type VARCHAR(32),
    removed    BOOLEAN                  NOT NULL DEFAULT false,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_setting_value PRIMARY KEY (id)
);

ALTER TABLE user_setting_value
    ADD CONSTRAINT uq_user_setting_value_scope UNIQUE (tenant_id, scope_type, scope_id, setting_key);

-- Exactly the predicate loadForScopes issues, in column order: one IN per layer, served directly by
-- this index.
CREATE INDEX idx_user_setting_value_lookup
    ON user_setting_value (tenant_id, scope_type, scope_id);

-- For the administrative question "who has overridden this setting", which is by key rather than by
-- scope.
CREATE INDEX idx_user_setting_value_key
    ON user_setting_value (tenant_id, setting_key);
--rollback DROP TABLE user_setting_value
