--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0002-second dbms:postgresql
CREATE TABLE b (id UUID NOT NULL);
--rollback DROP TABLE b
