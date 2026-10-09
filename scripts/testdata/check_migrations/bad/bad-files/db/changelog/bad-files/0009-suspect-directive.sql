--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0009-suspect-directive dbms:postgresql
-- A wrapped prose sentence whose continuation line begins with a directive name, which is the
--     changeset shape Liquibase rejects the whole file over when it appears before the first one.
CREATE TABLE i (id UUID NOT NULL);
--rollback
--rollback DROP TABLE i
