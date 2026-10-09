--liquibase formatted sql

--changeset ludwig-pat:pat-0003-owner-index dbms:postgresql
--comment Listing an owner's tokens is the management API's most common read; purging walks expiry.
CREATE INDEX ix_ludwig_pat_owner ON ludwig_pat (owner_subject);
CREATE INDEX ix_ludwig_pat_expires_at ON ludwig_pat (expires_at);
--rollback DROP INDEX ix_ludwig_pat_owner;
--rollback DROP INDEX ix_ludwig_pat_expires_at;
