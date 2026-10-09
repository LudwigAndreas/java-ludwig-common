--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0011-badname dbms:postgresql
CREATE TABLE k (id UUID NOT NULL);
--rollback DROP TABLE k
