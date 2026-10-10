--liquibase formatted sql

-- The sample application's one table. It exists so that the seed fixtures have something to write
-- and the reset mode has something to empty; nothing about its columns matters beyond that.

--changeset ludwig-localsample:localsample-0001-create-widget dbms:postgresql
CREATE TABLE sample_widget (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    note TEXT
);
--rollback DROP TABLE sample_widget
