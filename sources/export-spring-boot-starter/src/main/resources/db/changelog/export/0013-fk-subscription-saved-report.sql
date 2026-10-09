--liquibase formatted sql

-- RESTRICT rather than CASCADE, unlike the outputs. Deleting a saved report that something is
-- subscribed to should fail and say so: the subscription is somebody's standing expectation of a file
-- arriving, and silently cancelling it is the kind of deletion nobody notices until the report stops
-- turning up.

--changeset ludwig-export:export-0013-fk-subscription-saved-report dbms:postgresql
--comment RESTRICT, so deleting a subscribed-to saved report fails loudly instead of silently.
ALTER TABLE export_report_subscription
    ADD CONSTRAINT fk_export_subscription_saved_report FOREIGN KEY (saved_report_id)
        REFERENCES export_saved_report (id) ON DELETE RESTRICT;
--rollback ALTER TABLE export_report_subscription DROP CONSTRAINT fk_export_subscription_saved_report
