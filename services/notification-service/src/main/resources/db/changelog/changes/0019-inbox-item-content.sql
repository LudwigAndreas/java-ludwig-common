--liquibase formatted sql

-- The rendered body of one inbox item, keyed by the item id so the one-to-one is structural.
--
-- A second table for exactly the same two reasons notification_delivery_content is one: the item row
-- is what the list, the default filter and the unread count scan, and it wants to stay narrow rather
-- than carry a multi-kilobyte body; and content in its own table gives the retention purge a single
-- cheap target.
--
-- What it is NOT is a row in notification_delivery_content, and the difference is the retention
-- window. That table is purged at retention.content-ttl, seven days by default, with a startup check
-- that a body never outlives the delivery that owns it. An unread inbox item has to survive somebody
-- going on holiday. It is also a different hazard class: a delivery body is kept so an operator can
-- answer "what did we send?", while an inbox body is the thing the recipient is MEANT to read.
--
-- Rendered here at fan-out rather than at read time, which is the opposite of what this service does
-- for every other channel. A passive channel has no later send step to render at, and deferring the
-- render would not merely be awkward - retention.recipient-data-ttl scrubs the variable map after
-- seven days, so the content of an item still unread in week three could no longer be produced at
-- all.

--changeset ludwig-notification:notification-0019-inbox-item-content dbms:postgresql
--comment Rendered inbox bodies, keyed by the item id, purged on the inbox window and not the delivery one.
CREATE TABLE notification_inbox_item_content (
    id          UUID                     NOT NULL,
    subject     VARCHAR(998),
    body_html   TEXT,
    body_text   TEXT,
    rendered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_inbox_item_content PRIMARY KEY (id),
    CONSTRAINT fk_notification_inbox_content_item FOREIGN KEY (id)
        REFERENCES notification_inbox_item (id) ON DELETE CASCADE
);
--rollback DROP TABLE notification_inbox_item_content
