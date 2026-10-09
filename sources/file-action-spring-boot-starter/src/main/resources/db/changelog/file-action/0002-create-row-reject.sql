--liquibase formatted sql

-- A bounded sample of the rows that were refused, for the paged rejects endpoint. Bounded because the
-- full set is an artifact in the object store: a ten-thousand-reject upload would otherwise put ten
-- thousand rows in here, on a table nobody queries except to show the first page of them.
--
-- Column notes:
--
--   displayed_row  The row number as the spreadsheet displays it, 1-based and including the header.
--                  The off-by-one between this and a 0-based data-row ordinal is the single most
--                  common complaint about an import error report, which is why the column says which
--                  one it is.
--   column_header  The column's header name as the binding declares it, never an index: an index is
--                  wrong the moment somebody inserts a column, and it is unactionable to a user
--                  either way.
--   args           The message arguments, newline-separated. Not a formatted message, for the same
--                  reason the submission's failure_code is not: a reject is rendered when it is read,
--                  in the reader's locale, which may not be the locale that produced it.

--changeset ludwig-file-action:file-action-0002-create-row-reject dbms:postgresql
--comment A bounded sample of refused rows, for the paged rejects endpoint.
CREATE TABLE file_action_row_reject (
    id            UUID                     NOT NULL,
    submission_id UUID                     NOT NULL,
    sheet         VARCHAR(255),
    displayed_row INTEGER                  NOT NULL,
    column_header VARCHAR(255),
    code          VARCHAR(128)             NOT NULL,
    args          TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_file_action_row_reject PRIMARY KEY (id),
    CONSTRAINT fk_file_action_row_reject_submission FOREIGN KEY (submission_id)
        REFERENCES file_action_submission (id) ON DELETE CASCADE
);

CREATE INDEX ix_file_action_row_reject_submission
    ON file_action_row_reject (submission_id, displayed_row);
--rollback DROP TABLE file_action_row_reject
