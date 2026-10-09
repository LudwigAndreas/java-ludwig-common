--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-9999-something-else dbms:postgresql
CREATE TABLE j (id UUID NOT NULL);
--rollback DROP TABLE j
