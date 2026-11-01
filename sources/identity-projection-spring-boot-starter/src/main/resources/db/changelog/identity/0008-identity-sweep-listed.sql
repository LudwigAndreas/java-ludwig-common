--liquibase formatted sql

-- The subjects a complete listing of one identity source contained, staged while the listing runs. When the
-- listing completes, every active user of that source with no row here is disabled in one set-based statement -
-- unless that would disable more than ludwig.identity.sweep.max-disable-ratio of them - and the sweep's rows are
-- deleted. A table rather than an in-memory set, so a directory of a million users costs no heap; a staging table
-- rather than a "last seen" column, so a sweep does not rewrite every user row of the source.
--
-- Rows of a sweep that was never completed or abandoned (the replica died) are deleted by a later sweep once older
-- than a day; listed_at is what that cleanup reads.

--changeset ludwig-identity:identity-0008-identity-sweep-listed dbms:postgresql
CREATE TABLE identity_sweep_listed (
    sweep_id  UUID                     NOT NULL,
    subject   VARCHAR(255)             NOT NULL,
    listed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_identity_sweep_listed PRIMARY KEY (sweep_id, subject)
);
CREATE INDEX idx_identity_sweep_listed_at ON identity_sweep_listed (listed_at);
CREATE INDEX idx_security_user_source_status ON security_user (source_system, status);
--rollback DROP INDEX idx_security_user_source_status;
--rollback DROP TABLE identity_sweep_listed;
