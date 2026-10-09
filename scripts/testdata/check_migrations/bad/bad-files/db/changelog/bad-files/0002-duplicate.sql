--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0002-duplicate dbms:postgresql
CREATE TABLE l (id UUID NOT NULL);
--rollback DROP TABLE l
