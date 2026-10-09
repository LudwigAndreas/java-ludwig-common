--liquibase formatted sql

-- Verified contact data, projected only when ludwig.identity.contact.enabled is set - which it is not
-- by default. The OIDC provider is where verification happens and is therefore the single source of
-- truth for a person's addresses; the alternative, each service keeping its own contact table,
-- produces as many half-correct copies as there are services and no answer to which one is right.
--
-- Deliberately not indexed. Every lookup is by subject, so an index would buy nothing and would make
-- "which user has this address" a cheap question against a replica of directory data.
--
-- email_verified and phone_verified are nullable on purpose: null means the provider did not say,
-- which is a different fact from "it said no", and a consumer deciding whether it may write to an
-- address has to be able to tell them apart.

--changeset ludwig-identity:identity-0007-security-user-contact dbms:postgresql
--comment Verified contact columns, projected only when ludwig.identity.contact.enabled is set.
ALTER TABLE security_user
    ADD COLUMN email           VARCHAR(320),
    ADD COLUMN alternate_email VARCHAR(320),
    ADD COLUMN phone_number    VARCHAR(32),
    ADD COLUMN chat_handle     VARCHAR(255),
    ADD COLUMN email_verified  BOOLEAN,
    ADD COLUMN phone_verified  BOOLEAN;
--rollback ALTER TABLE security_user DROP COLUMN email, DROP COLUMN alternate_email, DROP COLUMN phone_number, DROP COLUMN chat_handle, DROP COLUMN email_verified, DROP COLUMN phone_verified
