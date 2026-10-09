--liquibase formatted sql

-- Only three layers are ever stored. PLATFORM comes from configuration so it can be hot-reloaded, and
-- DEFAULT is declared in code - a row claiming either would be resolved ahead of layers that are
-- real, and nothing in the application would report it.

--changeset ludwig-user-settings:user-settings-0002-user-setting-value-scope-check dbms:postgresql
--comment Constrains the stored layers to three, and makes a tombstone unambiguous.
ALTER TABLE user_setting_value
    ADD CONSTRAINT ck_user_setting_value_scope_type
    CHECK (scope_type IN ('USER', 'ROLE', 'TENANT'));

-- A live row always has a value and an encoding; a tombstone never does. Without this, "removed" and
-- "set to null" would both be representable and would resolve differently depending on which check a
-- reader happened to apply.
ALTER TABLE user_setting_value
    ADD CONSTRAINT ck_user_setting_value_tombstone
    CHECK ((removed AND value_text IS NULL AND value_type IS NULL)
        OR (NOT removed AND value_text IS NOT NULL AND value_type IS NOT NULL));
--rollback ALTER TABLE user_setting_value DROP CONSTRAINT ck_user_setting_value_tombstone;
--rollback ALTER TABLE user_setting_value DROP CONSTRAINT ck_user_setting_value_scope_type;
