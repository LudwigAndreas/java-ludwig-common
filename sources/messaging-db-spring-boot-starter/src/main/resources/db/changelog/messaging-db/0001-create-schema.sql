--liquibase formatted sql

-- Shared by every service on the database, and each of them may run this changeset against tables
-- another one already created: hence IF NOT EXISTS, and the advisory lock that serializes two services
-- starting at once. No rollback: one service rolling back must not drop the log every other service is
-- using. Dropping it is a deliberate operator step, in the module README.
--changeset ludwig-messaging-db:messaging-db-0001-create-schema dbms:postgresql
--comment The schema the log lives in.
SELECT pg_advisory_xact_lock(hashtext('ludwig-messaging-db'));
CREATE SCHEMA IF NOT EXISTS ${messaging_db_schema};
--rollback NOT REQUIRED
