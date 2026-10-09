--liquibase formatted sql

-- Unique indexes on both key ids. Unique rather than merely indexed: a collision is then a refused
-- insert rather than two tokens that verify against each other's digest, and uniqueness is what makes
-- the lookup a point read.
--
-- The second index is partial, because previous_key_id is null for every token that has never been
-- rotated - which is most of them - and a non-partial unique index would permit exactly ONE such row
-- in the entire table.
--
-- This is schema DDL, not data access, and it is NOT an exception to the module's QueryDSL-only rule:
-- SqlConfinementTest scans src/main/java, every query in this module is a QueryDSL predicate against a
-- generated Q-type, and there is no third SQL carve-out. Said here because a reader who finds SQL in
-- this module will reasonably wonder - and since this change, every changeset in the repository is
-- SQL, so the question comes up everywhere rather than only here.

--changeset ludwig-pat:pat-0002-unique-key-ids dbms:postgresql
--comment Unique indexes on both key ids; the previous-key one is partial.
CREATE UNIQUE INDEX ux_ludwig_pat_key_id ON ludwig_pat (key_id);

CREATE UNIQUE INDEX ux_ludwig_pat_previous_key_id
    ON ludwig_pat (previous_key_id)
    WHERE previous_key_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS ux_ludwig_pat_previous_key_id;
--rollback DROP INDEX IF EXISTS ux_ludwig_pat_key_id;
