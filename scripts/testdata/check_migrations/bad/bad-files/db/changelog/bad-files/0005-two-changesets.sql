--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0005-two-changesets dbms:postgresql
CREATE TABLE e (id UUID NOT NULL);
--rollback DROP TABLE e

--changeset ludwig-bad-files:bad-files-0005-two-changesets-again dbms:postgresql
CREATE TABLE f (id UUID NOT NULL);
--rollback DROP TABLE f
