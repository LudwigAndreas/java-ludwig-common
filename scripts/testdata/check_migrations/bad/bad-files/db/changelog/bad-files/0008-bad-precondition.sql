--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0008-bad-precondition dbms:postgresql
--preconditions onFail:MARK_RAN
--precondition-column-exists tableName:a columnName:id
ALTER TABLE a ADD COLUMN label VARCHAR(16);
--rollback ALTER TABLE a DROP COLUMN label
