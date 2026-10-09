--liquibase formatted sql

--changeset ludwig-export:export-0011-index-saved-report-definition dbms:postgresql
--comment "Which saved reports exist for this definition" - every admin screen's opening listing.
CREATE INDEX idx_export_saved_report_definition
    ON export_saved_report (definition_key, name);
--rollback DROP INDEX idx_export_saved_report_definition
