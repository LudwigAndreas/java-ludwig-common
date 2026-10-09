--liquibase formatted sql

--changeset ludwig-bad-files:bad-files-0006-no-dbms
CREATE TABLE g (id UUID NOT NULL);
--rollback DROP TABLE g
