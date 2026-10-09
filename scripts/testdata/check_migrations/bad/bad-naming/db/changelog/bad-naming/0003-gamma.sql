--liquibase formatted sql

--changeset someone-else:bad-naming-0003-gamma dbms:postgresql
CREATE TABLE gamma (id UUID NOT NULL);
--rollback DROP TABLE gamma
