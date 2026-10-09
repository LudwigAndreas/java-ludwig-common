--liquibase formatted sql

-- One row per ingest run, carrying its identity, its checkpoint and its balance numbers.
--
-- The identity triple (container, object_key, content_identity) and its unique constraint ARE the
-- exactly-once guard - not a `processed` flag somebody remembers to check, because a flag is read and
-- then acted on and two replicas reading it in the same moment both act. content_identity is the
-- third column and the one that keeps the guard honest: keying on the key alone means a partner
-- re-uploading a CORRECTED file under the same name is skipped as a duplicate, which is silent data
-- loss discovered weeks later.
--
-- The checkpoint is advanced in the SAME transaction as the batch it describes - see IngestRunner,
-- where that invariant lives. It is a column on this row rather than a separate progress table
-- precisely so that it cannot be written in a second transaction: two transactions give duplicates or
-- a gap depending on which commits first, and there is no ordering of them that is safe.
--
-- receipt_written and archived record the side effects that happen AFTER the run has SUCCEEDED, so a
-- crash between them does not have to guess. The order is deliberate and stated in IngestRunner:
-- status first because it is the source of truth, then the receipt, then the archive. The reverse
-- leaves a bucket that says "done" and a database that says "never ran".

--changeset ludwig-file-ingest:file-ingest-0001-create-run dbms:postgresql
--comment One ingest run: its identity triple, its checkpoint and its balance numbers.
CREATE TABLE file_ingest_run (
    id                  UUID                     NOT NULL,
    task                VARCHAR(128)             NOT NULL,
    container           VARCHAR(255)             NOT NULL,
    object_key          VARCHAR(1024)            NOT NULL,
    content_identity    VARCHAR(255)             NOT NULL,
    source_uri          VARCHAR(2048)            NOT NULL,
    status              VARCHAR(32)              NOT NULL,
    checkpoint_kind     VARCHAR(32)              NOT NULL,
    checkpoint_position BIGINT                   NOT NULL DEFAULT 0,
    records_committed   BIGINT                   NOT NULL DEFAULT 0,
    bytes_read          BIGINT                   NOT NULL DEFAULT 0,
    records_read        BIGINT                   NOT NULL DEFAULT 0,
    records_applied     BIGINT                   NOT NULL DEFAULT 0,
    records_quarantined BIGINT                   NOT NULL DEFAULT 0,
    records_skipped     BIGINT                   NOT NULL DEFAULT 0,
    expected_records    BIGINT,
    locked_by           VARCHAR(255),
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at         TIMESTAMP WITH TIME ZONE,
    failure_message     VARCHAR(4000),
    receipt_written     BOOLEAN                  NOT NULL DEFAULT false,
    archived            BOOLEAN                  NOT NULL DEFAULT false,
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_file_ingest_run PRIMARY KEY (id)
);

ALTER TABLE file_ingest_run
    ADD CONSTRAINT ux_file_ingest_run_identity UNIQUE (container, object_key, content_identity);
--rollback DROP TABLE file_ingest_run
