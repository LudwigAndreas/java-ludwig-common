--liquibase formatted sql

--changeset ludwig-bad-naming:bad-naming-0001-alpha dbms:postgresql
CREATE TABLE alpha (id UUID NOT NULL);
--rollback DROP TABLE alpha
