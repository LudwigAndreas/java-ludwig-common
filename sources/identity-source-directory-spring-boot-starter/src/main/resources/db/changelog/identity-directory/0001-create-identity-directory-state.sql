--liquibase formatted sql

-- One row per directory-fed identity source: how far its incremental sync has read, and how its last sync and last
-- sweep ended. The watermark is written in the same transaction as the page of changes it ends, so a sync stopped
-- mid-way resumes after the last committed page rather than from the start or past a page it never applied.
--
-- error_code is an exception type, never a message: a provider's error text can carry the user data it choked on.

--changeset ludwig-identity-directory:identity-directory-0001-create-identity-directory-state dbms:postgresql
CREATE TABLE identity_directory_state (
    source              VARCHAR(64)              NOT NULL,
    watermark           TIMESTAMP WITH TIME ZONE,
    last_sync_at        TIMESTAMP WITH TIME ZONE,
    last_sync_outcome   VARCHAR(16),
    last_sync_error     VARCHAR(255),
    last_sync_pages     INTEGER,
    last_sync_changes   INTEGER,
    last_sweep_at       TIMESTAMP WITH TIME ZONE,
    last_sweep_outcome  VARCHAR(16),
    last_sweep_error    VARCHAR(255),
    last_sweep_pages    INTEGER,
    last_sweep_disabled INTEGER,
    CONSTRAINT pk_identity_directory_state PRIMARY KEY (source)
);
--rollback DROP TABLE identity_directory_state;
