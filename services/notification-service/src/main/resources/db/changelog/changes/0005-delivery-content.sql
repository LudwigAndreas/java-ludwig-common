--liquibase formatted sql

-- Rendered bodies, in their own table rather than as columns on notification_delivery. The delivery
-- row is claimed and updated thousands of times a minute and wants to stay narrow, and rendered
-- content is the most sensitive thing this service holds - so giving it its own table gives the
-- retention purge a single cheap target on a much shorter schedule. Its primary key IS the delivery
-- id, which makes the one-to-one structural.

--changeset ludwig-notification:notification-0005-delivery-content dbms:postgresql
--comment Rendered bodies, keyed by the delivery id so the one-to-one is structural.
CREATE TABLE notification_delivery_content (
    id          UUID                     NOT NULL,
    subject     VARCHAR(998),
    body_html   TEXT,
    body_text   TEXT,
    rendered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    purge_after TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_delivery_content PRIMARY KEY (id),
    CONSTRAINT fk_notification_content_delivery FOREIGN KEY (id)
        REFERENCES notification_delivery (id) ON DELETE CASCADE
);

CREATE INDEX ix_notification_content_purge ON notification_delivery_content (purge_after);
--rollback DROP TABLE notification_delivery_content
