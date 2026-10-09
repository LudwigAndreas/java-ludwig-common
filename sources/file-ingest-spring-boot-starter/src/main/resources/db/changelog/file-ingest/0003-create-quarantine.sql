--liquibase formatted sql

-- One row per record the run refused, with enough position information to find it again.
--
-- Position, twice over. The ordinal is what a person counts to in the file; the byte offset is what a
-- tool seeks to. A row with only a count sends whoever reads it back to the source object to find a
-- line they cannot address.
--
-- stage is PARSE / APPLY / OVERSIZED: the first question about a quarantine spike is which.
--
-- raw_record is truncated by the engine at the task's quarantine.max-record-length. The record that
-- failed is disproportionately likely to be the enormous one.
--
-- No foreign key to file_ingest_run, deliberately. The quarantine rows are the evidence about a run,
-- and a cascade that removed them with the run - or a constraint that refused to remove the run while
-- they existed - would make retention of the two a single decision. They are not one decision: a run
-- row is small and worth keeping for a long time, and a quarantine row can be 8 KB of raw record.

--changeset ludwig-file-ingest:file-ingest-0003-create-quarantine dbms:postgresql
--comment One row per refused record, addressable by both ordinal and byte offset.
CREATE TABLE file_ingest_quarantine (
    id             UUID                     NOT NULL,
    run_id         UUID                     NOT NULL,
    task           VARCHAR(128)             NOT NULL,
    record_ordinal BIGINT                   NOT NULL,
    byte_offset    BIGINT                   NOT NULL,
    stage          VARCHAR(32)              NOT NULL,
    raw_record     TEXT,
    error          VARCHAR(4000)            NOT NULL,
    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_file_ingest_quarantine PRIMARY KEY (id)
);

CREATE INDEX ix_file_ingest_quarantine_run
    ON file_ingest_quarantine (run_id, record_ordinal);
--rollback DROP TABLE file_ingest_quarantine
