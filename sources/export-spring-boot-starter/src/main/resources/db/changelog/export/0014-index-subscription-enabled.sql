--liquibase formatted sql

--changeset ludwig-export:export-0014-index-subscription-enabled dbms:postgresql
--comment Partial on enabled: the scheduler reads only the live ones, every tick, forever.
CREATE INDEX idx_export_subscription_enabled
    ON export_report_subscription (saved_report_id)
    WHERE enabled = true;
--rollback DROP INDEX IF EXISTS idx_export_subscription_enabled
