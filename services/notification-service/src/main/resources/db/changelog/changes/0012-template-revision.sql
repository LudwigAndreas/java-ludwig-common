--liquibase formatted sql

-- Which template text produced which message. Templates are files and are hot-reloaded, so the
-- directory is not a historical record - by the time somebody asks what wording a customer received in
-- March, the file has been edited twice. Every render hashes its source and records the hash on the
-- delivery; this table maps a hash back to readable text.
--
-- revision is human-readable numbering, computed as max+1 and therefore best-effort under a race. The
-- hash is what identifies the text and it is unique by construction.

--changeset ludwig-notification:notification-0012-template-revision dbms:postgresql
--comment Maps a template content hash back to the readable source that produced a message.
CREATE TABLE notification_template_revision (
    id            UUID                     NOT NULL,
    template_name VARCHAR(512)             NOT NULL,
    content_hash  VARCHAR(64)              NOT NULL,
    revision      INTEGER                  NOT NULL,
    source        TEXT                     NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_template_revision PRIMARY KEY (id),
    CONSTRAINT uk_notification_template_revision UNIQUE (template_name, content_hash)
);
--rollback DROP TABLE notification_template_revision
