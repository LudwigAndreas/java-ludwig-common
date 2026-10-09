--liquibase formatted sql

--changeset ludwig-probe:probe-0002-guard-thing dbms:postgresql
--comment Guarded so a consumer without the legacy table records it as run rather than failing.
--preconditions onFail:MARK_RAN
--precondition-table-exists table:probe_thing
--precondition-sql-check expectedResult:0 SELECT count(*) FROM probe_thing WHERE name IS NULL
CREATE UNIQUE INDEX ux_probe_thing_name ON probe_thing (name);
--rollback NOT REQUIRED
