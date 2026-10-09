--liquibase formatted sql

--changeset ludwig-bad-layout:bad-layout-0002-orphan dbms:postgresql
CREATE TABLE orphan (id UUID NOT NULL);
--rollback DROP TABLE orphan
