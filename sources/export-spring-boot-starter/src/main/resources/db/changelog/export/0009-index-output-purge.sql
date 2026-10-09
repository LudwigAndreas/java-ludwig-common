--liquibase formatted sql

-- The purge index: files past their expiry that still have bytes behind them. Partial on
-- purged_at IS NULL because the purge runs every hour and the set it is looking for is almost always
-- empty - an index over every output ever produced would be scanned hourly to find nothing.

--changeset ludwig-export:export-0009-index-output-purge dbms:postgresql
--comment The purge index: expired outputs that still have bytes behind them.
CREATE INDEX idx_export_report_output_purge
    ON export_report_output (expires_at)
    WHERE purged_at IS NULL;
--rollback DROP INDEX IF EXISTS idx_export_report_output_purge
