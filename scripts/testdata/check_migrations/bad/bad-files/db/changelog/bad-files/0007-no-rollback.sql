--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0007-no-rollback dbms:postgresql
CREATE TABLE h (id UUID NOT NULL);
