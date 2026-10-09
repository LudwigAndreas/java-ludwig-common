--liquibase formatted sql

-- A dimension without a value would be read as a denial at runtime; rejecting it at write time
-- instead means the malformed row never exists to be misread.

--changeset ludwig-identity:identity-0006-security-grant-dimension-check dbms:postgresql
--comment A dimension with no value would read as a denial, so it is refused at write time.
ALTER TABLE security_grant
    ADD CONSTRAINT ck_security_grant_dimension_value
    CHECK (dimension IS NULL OR dimension_value IS NOT NULL);
--rollback ALTER TABLE security_grant DROP CONSTRAINT ck_security_grant_dimension_value
