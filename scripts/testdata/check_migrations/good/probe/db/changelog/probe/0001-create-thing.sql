--liquibase formatted sql

-- Prose rationale lives here, above the changeset, because a repeated --comment keeps only its
-- last line - so the paragraph goes in plain SQL comments and --comment carries the summary.

--changeset ludwig-probe:probe-0001-create-thing dbms:postgresql
--comment The one table this fixture owns.
CREATE TABLE probe_thing (
    id   UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_probe_thing PRIMARY KEY (id)
);
--rollback DROP TABLE probe_thing
