--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0001-first dbms:postgresql
CREATE TABLE a (id UUID NOT NULL);
--rollback DROP TABLE a
