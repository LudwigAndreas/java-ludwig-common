--liquibase formatted sql

-- The rendered body of one announcement, in one language.
--
-- Keyed (announcement_id, locale), so the row count follows the number of languages the deployment
-- ships bundles for and NEVER the size of the audience. Two rows today (en, ru), whether the
-- announcement reaches ten people or a hundred thousand - which is what keeps the one-row property
-- of the aggregate intact while still showing each recipient their own language.
--
-- Rendered at publish, and every supported locale is rendered BEFORE anything is inserted, so a
-- template that compiles in English and fails in Russian rejects the whole publish rather than
-- producing a half-translated announcement. Rendering at read time is not an option for the same
-- reason the inbox renders at fan-out: retention.recipient-data-ttl scrubs the variable map, so an
-- announcement in its third week could no longer be produced.
--
-- A recipient whose locale has no row gets the default locale's content in full - the same fallback
-- rule TemplateCoordinates already applies when resolving a template file, rather than a second
-- rule a reader would have to discover.

--changeset ludwig-notification:notification-0021-announcement-content dbms:postgresql
--comment One rendered body per supported locale; the count follows languages, never audience size.
CREATE TABLE notification_announcement_content (
    announcement_id UUID                     NOT NULL,
    locale          VARCHAR(35)              NOT NULL,
    subject         VARCHAR(998),
    body_html       TEXT,
    body_text       TEXT,
    rendered_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_announcement_content PRIMARY KEY (announcement_id, locale),
    CONSTRAINT fk_notification_announcement_content FOREIGN KEY (announcement_id)
        REFERENCES notification_announcement (id) ON DELETE CASCADE
);
--rollback DROP TABLE notification_announcement_content
