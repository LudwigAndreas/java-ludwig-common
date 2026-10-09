--liquibase formatted sql

-- The platform settled on one vocabulary for long-running operations
-- (ru.ludwigandreas.webcore.operation.OperationStatus) and its word for a finished run is SUCCEEDED.
-- This module called the same state COMPLETED; export already called it SUCCEEDED and had that word
-- in a published REST API, so this is the side that moved.
--
-- A data migration rather than a mapping in the entity, because a value stored under two spellings is
-- a value every future query has to remember to check twice - and the index on
-- (task, status, started_at) would then be probed with an IN list forever. One UPDATE now, once, and
-- the column has one vocabulary again.
--
-- A guard rather than a bare UPDATE, so that a clean install, where no row can exist, does not record
-- a changeset that did nothing surprising, and so that re-running against an already-migrated
-- database is a no-op rather than an error.

--changeset ludwig-file-ingest:file-ingest-0004-rename-completed-to-succeeded dbms:postgresql
--comment Renames the COMPLETED status to the platform's SUCCEEDED.
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT CASE WHEN EXISTS (SELECT 1 FROM file_ingest_run WHERE status = 'COMPLETED') THEN 1 ELSE 0 END
UPDATE file_ingest_run SET status = 'SUCCEEDED' WHERE status = 'COMPLETED';
--rollback UPDATE file_ingest_run SET status = 'COMPLETED' WHERE status = 'SUCCEEDED'
