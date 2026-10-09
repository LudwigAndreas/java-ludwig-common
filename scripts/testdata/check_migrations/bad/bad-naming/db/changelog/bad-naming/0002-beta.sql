--liquibase formatted sql

--changeset ludwig-elsewhere:elsewhere-0002-beta dbms:postgresql
CREATE TABLE beta (id UUID NOT NULL);
--rollback DROP TABLE beta
